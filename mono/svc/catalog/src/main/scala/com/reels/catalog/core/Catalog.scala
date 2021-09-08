package com.reels.catalog.core

import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate}
import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.{Source, SourceWithContext}
import cats.syntax.all._
import com.reels.catalog.idx.{MovieIndex, SearchParameters}
import com.reels.catalog.repo.{FindParameters, MovieRepo}
import com.reels.constructs.{Page, PaginationParameters}
import io.scalaland.chimney.dsl._

case class Language(
  code: String,
  name: String,
)

case class Country(
  code: String,
  name: String,
)

case class Genre(
  id: String,
  name: String,
)

case class SearchQuery(
  term: String,
)

case class Movie(
  id: String,
  title: String,
  tagline: Option[String],
  overview: Option[String],
  spokenLanguages: Seq[Language],
  productionCountries: Seq[Country],
  genres: Seq[Genre],
  releaseDate: Option[LocalDate],
  foreignUrls: Map[String, String],
  created: Instant,
  updated: Instant,
  indexed: Option[Instant],
  deleted: Option[Instant],
)

case class MovieChangeset(
  title: Option[String],
  tagline: Option[Option[String]],
  overview: Option[Option[String]],
  spokenLanguages: Option[Seq[Language]],
  productionCountries: Option[Seq[Country]],
  genres: Option[Seq[Genre]],
  releaseDate: Option[Option[LocalDate]],
  foreignUrls: Option[Map[String, String]],
  updated: Option[Instant],
  indexed: Option[Option[Instant]],
  deleted: Option[Option[Instant]],
)

object MovieChangeset {
  def empty: MovieChangeset = MovieChangeset(
    none, none, none, none, none, none, none, none, none, none, none
  )
}

case class CreateMovieParams(
  title: String,
  tagline: Option[String],
  overview: Option[String],
  spokenLanguages: Seq[Language],
  productionCountries: Seq[Country],
  genres: Seq[Genre],
  releaseDate: Option[LocalDate],
  foreignUrls: Map[String, String],
) {
  def generateId: String = UUID.nameUUIDFromBytes(
    s"${releaseDate.getOrElse("0000-00-00")}::${title}::${productionCountries.map(_.code).mkString(",")}"
      .getBytes(StandardCharsets.UTF_8)).toString

  def toMovie: Movie = {
    val now = Instant.now()
    this.into[Movie]
      .withFieldComputed(_.id, _.generateId)
      .withFieldComputed(_.created, _ => now)
      .withFieldComputed(_.updated, _ => now)
      .withFieldConst(_.indexed, none)
      .withFieldConst(_.deleted, none)
      .transform
  }
}

case class UpdateMovieParams(
    title: Option[String],
    tagline: Option[Option[String]],
    overview: Option[Option[String]],
    spokenLanguages: Option[Seq[Language]],
    productionCountries: Option[Seq[Country]],
    genres: Option[Seq[Genre]],
    releaseDate: Option[Option[LocalDate]],
    foreignUrls: Option[Map[String, String]],
) {
  def toChangeset: MovieChangeset = {
    this.into[MovieChangeset]
      .withFieldComputed(_.updated, _ => Instant.now().some)
      .withFieldConst(_.indexed, none)
      .withFieldConst(_.deleted, none)
      .transform
  }
}

case class IndexMovie() {
  def toChangeset: MovieChangeset = {
    MovieChangeset.empty.copy(indexed = Instant.now().some.some)
  }
}

case class DeleteMovie() {
  def toChangeset: MovieChangeset = {
    MovieChangeset.empty.copy(deleted = Instant.now().some.some)
  }
}

sealed trait CatalogError
case class IdAlreadyExists(id: String) extends CatalogError
case class DBDeserializationError(throwable: Throwable) extends CatalogError
case class DBQueryError(throwable: Throwable) extends CatalogError

class Catalog(repo: MovieRepo, idx: MovieIndex) {
  private val defaultPageSize = 25

  private def findParams(
    pagination: PaginationParameters,
  ): FindParameters = FindParameters(
    pageSize = pagination.count.getOrElse(defaultPageSize),
    anchor = pagination.anchor,
  )

  private def searchParams(
    pagination: PaginationParameters,
  ): SearchParameters = SearchParameters(
    pageSize = pagination.count.getOrElse(defaultPageSize),
    anchor = pagination.anchor,
  )

  def createMovie[A](
    params: CreateMovieParams,
    context: A,
  ): SourceWithContext[Either[CatalogError, Movie], A, NotUsed] =
    SourceWithContext.fromTuples[Movie, A, NotUsed](Source.single((params.toMovie, context)))
      .via(repo.createMovie)

  def updateMovie[A](
    id: String,
    params: UpdateMovieParams,
    context: A,
  ): SourceWithContext[Either[CatalogError, Movie], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((id, params.toChangeset), context)))
      .via(repo.updateMovie)

  def deleteMovie[A](
    id: String,
    context: A,
  ): SourceWithContext[Either[CatalogError, Boolean], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((id, DeleteMovie().toChangeset), context)))
      .via(repo.softDeleteMovie[A])
      .map(_.map(_.deleted.nonEmpty))

  def deleteSoftDeleted[A](
    context: A,
  ): SourceWithContext[Either[CatalogError, Int], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((), context)))
      .via(repo.deleteSoftDeleted)

  def findOneMovie[A](
    id: String,
    context: A,
  ): SourceWithContext[Either[CatalogError, Option[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single((id, context)))
      .via(repo.findOneMovie)

  def findMovies[A](
    pagination: PaginationParameters,
    context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single((findParams(pagination), context)))
      .via(repo.findMovies)

  def findMoviesWithIds[A](
    ids: Seq[String],
    context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single((ids, context)))
      .via(repo.findMoviesWithIds)

  def searchMovies[A](
     query: SearchQuery,
     pagination: PaginationParameters,
     context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((query, searchParams(pagination)), context)))
      .via(idx.searchMovies)

  def createIndex[A](
    context: A,
  ): SourceWithContext[Either[CatalogError, Boolean], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((), context)))
      .via(idx.createIndex)

  def findMoviesToIndex[A](
    count: Int,
    context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single((FindParameters(count, None), context)))
      .via(repo.findStaleIndexed)

  def indexMovies[A](
    movies: Seq[Movie],
    context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single((movies, context)))
      .via(idx.indexMovies)

  def markMoviesIndexed[A](
    ids: Seq[String],
    context: A,
  ): SourceWithContext[Either[CatalogError, Page[Movie]], A, NotUsed] =
    SourceWithContext.fromTuples(Source.single(((ids, IndexMovie().toChangeset), context)))
      .via(repo.updateMovies)
}
