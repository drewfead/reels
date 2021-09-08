package com.reels.catalog.repo

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Flow, FlowWithContext, Sink, Source}
import cats.syntax.all._
import com.reels.catalog.core._
import com.reels.catalog.repo.DynamoDbMovieRepo.MovieSchema
import com.reels.constructs.{Page, PageWithErrors}
import com.reels.dynamodb.{Anchor, CirceSupport, DynamoFormat}
import com.reels.dynamodb.syntax._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.scalaland.chimney.dsl._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model._

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

object DynamoDbMovieRepo {
  object IndexStaleBy {
    def unapply(movie: Movie): Option[Long] = {
      val effectiveIndexed = movie.indexed.getOrElse(Instant.MIN)

      if (effectiveIndexed.isAfter(movie.updated)) {
        none
      } else {
        effectiveIndexed.until(movie.updated, ChronoUnit.MILLIS).some
      }
    }
  }

  object MovieSchema {
    def apply(movie: Movie): MovieSchema = movie.into[MovieSchema]
      .withFieldComputed(_.indexStaleBy, IndexStaleBy.unapply)
      .transform
  }

  case class MovieSchema(
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
    indexStaleBy: Option[Long],
  ) {
    def toMovie: Movie = this.transformInto[Movie]
  }

  object protocol extends CirceSupport {
    implicit val instantEncoder: Encoder[Instant] = Encoder.encodeLong.contramap(_.toEpochMilli)
    implicit val instantDecoder: Decoder[Instant] = Decoder.decodeLong.emap(Instant.ofEpochMilli(_).asRight)

    implicit val genreEncoder: Encoder[Genre] = deriveEncoder
    implicit val genreDecoder: Decoder[Genre] = deriveDecoder
    implicit val countryEncoder: Encoder[Country] = deriveEncoder
    implicit val countryDecoder: Decoder[Country] = deriveDecoder
    implicit val languageEncoder: Encoder[Language] = deriveEncoder
    implicit val languageDecoder: Decoder[Language] = deriveDecoder
    implicit val movieEncoder: Encoder[MovieSchema] = deriveEncoder
    implicit val movieDecoder: Decoder[MovieSchema] = deriveDecoder

    implicit val movieChangesetEncoder: Encoder[MovieChangeset] = deriveEncoder
  }

  object syntax {
    implicit class FindParametersOps(parameters: FindParameters) {
      def getAnchor: Option[Anchor] = parameters match {
        case FindParameters(_, Some(Anchor(anch))) => anch.some
        case _ => none
      }
    }

    implicit class FieldOptionOps(field: Option[Field]) {
      def orNotPresent: Node = field match {
        case Some(f) => f
        case _ => NotPresent
      }
    }
  }
}

class DynamoDbMovieRepo(
  client: DynamoDbAsyncClient,
  parallelism: Int,
)(implicit
  mat: Materializer,
  ec: ExecutionContext
) extends MovieRepo {
  val tableName = "catalog"
  val staleIndexedIdxName = "staleIndexedIdx"

  implicit val dynamoDb: DynamoDbAsyncClient = client
  import DynamoDbMovieRepo.protocol._
  import DynamoDbMovieRepo.syntax._

  override def findMovies[A]: FlowWithContext[FindParameters, A, Either[CatalogError, Page[Movie]], A, NotUsed] =
    FlowWithContext[FindParameters, A]
      .map { params =>
          ScanRequest.builder()
            .tableName(tableName)
            .fromAnchorIfPresent(params.getAnchor)
            .limit(params.pageSize)
            .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .map {
        case Success(resp) =>
          resp.itemsAs[MovieSchema].map { movies =>
            Page(
              items = movies.map(_.toMovie),
              nextAnchor = resp.nextAnchor.map(_.encoded),
            )
          }.left
            .map[CatalogError](err => DBDeserializationError(err))
        case Failure(err) =>
          DBQueryError(err).asLeft
      }

  override def findOneMovie[A]: FlowWithContext[String, A, Either[CatalogError, Option[Movie]], A, NotUsed] =
    FlowWithContext[String, A]
      .map { id =>
        GetItemRequest.builder()
          .tableName(tableName)
          .withKey(Map("id" -> StringField(id)))
          .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .map {
        case Success(resp) =>
          resp.itemAs[MovieSchema]
            .map(_.map(_.toMovie))
            .left
            .map[CatalogError](err => DBDeserializationError(err))
        case Failure(err) =>
          DBQueryError(err).asLeft
      }

  override def findMoviesWithIds[A]: FlowWithContext[Seq[String], A, Either[CatalogError, Page[Movie]], A, NotUsed] =
    FlowWithContext[Seq[String], A]
      .map { ids =>
        BatchGetItemRequest.builder()
          .withKeys(tableName, ids.map(id => Map("id" -> StringField(id))))
          .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .map {
        case Success(resp) =>
          resp.itemsFromTableAs[MovieSchema](tableName)
            .map(_.map(_.toMovie))
            .map(Page(0, none, _))
            .left
            .map[CatalogError](err => DBDeserializationError(err))
        case Failure(err) =>
          DBQueryError(err).asLeft
      }

  override def findStaleIndexed[A]: FlowWithContext[FindParameters, A, Either[CatalogError, Page[Movie]], A, NotUsed] =
    FlowWithContext[FindParameters, A]
      .map { params =>
          QueryRequest.builder()
            .tableName(tableName)
            .indexName(staleIndexedIdxName)
            .limit(params.pageSize)
            .fromAnchorIfPresent(params.getAnchor)
            .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .map {
        case Success(resp) =>
          resp.itemsAs[MovieSchema]
            .map { movies =>
              Page(
                items = movies.map(_.toMovie),
                nextAnchor = resp.nextAnchor.map(_.encoded),
              )
            }
            .left
            .map[CatalogError](err => DBDeserializationError(err))
        case Failure(err) =>
          DBQueryError(err).asLeft
      }

  private def juggleIntoContext[A, B]: FlowWithContext[A, B, A, (A, B), NotUsed] =
    Flow[(A, B)]
      .asFlowWithContext((a: A, b: B) => (a, b))(identity)
      .map(_._1)

  private def juggleOutOfContext[A, B, C]: FlowWithContext[A, (B, C), (A, B), C, NotUsed] =
    Flow[(A, (B, C))]
      .asFlowWithContext((a: A, bc: (B, C)) => (a, bc))(_._2)
      .mapContext(_._2)
      .map(abc => abc._1 -> abc._2._1)

  override def createMovie[A]: FlowWithContext[Movie, A, Either[CatalogError, Movie], A, NotUsed] = {
    FlowWithContext[Movie, A]
      .via(juggleIntoContext)
      .map { movie =>
        PutItemRequest.builder()
          .tableName(tableName)
          .withItemFrom[MovieSchema](MovieSchema(movie))
          .conditionExpression("attribute_not_exists(id)")
          .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .via(juggleOutOfContext)
      .map {
        case (Success(_), movie) => movie.asRight
        case (Failure(_: ConditionalCheckFailedException), movie) => IdAlreadyExists(movie.id).asLeft
        case (Failure(err), _) => DBQueryError(err).asLeft
      }
  }

  private def setOrNoop[T](path: String, option: Option[T]): Option[(String, String)] = {
    option.map(_ => ("SET" -> s"$path = :$path"))
  }

  private def setOrRemove[T](path: String, option: Option[Option[T]]): Option[(String, String)] = {
    option.map {
      case Some(_) => "SET" -> s"$path = :$path"
      case None => "REMOVE" -> s"$path"
    }
  }

  private def setOrRemove[T](path: String, option: Option[IterableOnce[T]]): Option[(String, String)] = {
    option.map { o =>
      if(o.iterator.isEmpty) {
        "REMOVE" -> s"$path"
      } else {
        "SET" -> s"$path = :$path"
      }
    }
  }

  private def updateExpressionFor(changeset: MovieChangeset): String = {
    Seq[Option[(String, String)]](
      setOrNoop("title", changeset.title),
      setOrRemove("tagline", changeset.tagline),
      setOrRemove("overview", changeset.overview),
      setOrRemove("releaseDate", changeset.releaseDate),
      setOrRemove("genres", changeset.genres),
      setOrRemove("productionCountries", changeset.productionCountries),
      setOrRemove("spokenLanguages", changeset.spokenLanguages),
      setOrNoop("updated", changeset.updated),
      setOrRemove("indexed", changeset.indexed),
      setOrRemove("deleted", changeset.deleted),
      (changeset.updated, changeset.indexed) match {
        case (Some(updated), Some(Some(indexed))) if indexed.compareTo(updated) >= 0 =>
          ("REMOVE" -> "indexStaleBy").some
        case (Some(_), Some(Some(_))) =>
          ("SET" -> "indexStaleBy = :updated - :indexed").some
        case (Some(_), Some(None)) =>
          ("SET" -> "indexStaleBy = :updated").some
        case (Some(_), None) =>
          ("SET" -> "indexStaleBy = :updated - indexed").some
        case (None, Some(_)) =>
          ("SET" -> "indexStaleBy = updated - :indexed").some
      }
    )
      .flatten
      .groupMap(_._1)(_._2)
      .map {
        case (keyWord, actions) => s"$keyWord ${actions.mkString(", ")}"
      }.mkString(" ")
  }

  private def expressionAttributeValuesFor(changeset: MovieChangeset): java.util.Map[String, AttributeValue] = {
    Seq[Option[Map[String, AttributeValue]]](
      changeset.title.flatMap(DynamoFormat.encode).map(t => Map(":title" -> t)),
      changeset.tagline.flatMap(DynamoFormat.encode).map(t => Map(":tagline" -> t)),
      changeset.overview.flatMap(DynamoFormat.encode).map(o => Map(":overview" -> o)),
      changeset.releaseDate.flatMap(DynamoFormat.encode).map(r => Map(":releaseDate" -> r)),
      changeset.foreignUrls.flatMap(DynamoFormat.encode).map(f => Map(":foreignUrls" -> f)),
      changeset.genres.flatMap(DynamoFormat.encode).map(g => Map(":genres" -> g)),
      changeset.productionCountries.flatMap(DynamoFormat.encode).map(p => Map(":productionCountries" -> p)),
      changeset.spokenLanguages.flatMap(DynamoFormat.encode).map(s => Map(":spokenLanguages" -> s)),
      changeset.updated.flatMap(DynamoFormat.encode).map(u => Map(":updated" -> u)),
      changeset.indexed.flatMap(DynamoFormat.encode).map(i => Map(":indexed" -> i)),
      changeset.deleted.flatMap(DynamoFormat.encode).map(d => Map(":deleted" -> d)),
    )
      .flatten
      .foldLeft(Map[String, AttributeValue]())(_ ++ _)
      .asJava
  }

  override def updateMovie[A]: FlowWithContext[(String, MovieChangeset), A, Either[CatalogError, Movie], A, NotUsed] =
    FlowWithContext[(String, MovieChangeset), A]
      .map { case (id, changeset) =>
        UpdateItemRequest.builder()
          .tableName(tableName)
          .withKey(Map("id" -> StringField(id)))
          .updateExpression(updateExpressionFor(changeset))
          .expressionAttributeValues(expressionAttributeValuesFor(changeset))
          .returnValues(ReturnValue.ALL_NEW)
          .build()
      }
      .via(DynamoDb.flowWithContext(parallelism))
      .map {
        case Success(resp) => resp.itemAs[MovieSchema] match {
          case Right(Some(m)) => m.toMovie.asRight
          case Right(None) => DBQueryError(new IllegalArgumentException("didn't get a projection")).asLeft
          case Left(err) => DBDeserializationError(err).asLeft
        }
      }

  override def updateMovies[A]: FlowWithContext[(Seq[String], MovieChangeset), A, PageWithErrors[CatalogError, Movie], A, NotUsed] =
    Flow[(Seq[String], MovieChangeset, A)]
      .mapAsync(parallelism) { case (ids, changeset, ctx) =>
        Source.fromIterator(() => ids.iterator.map(id => ((id, changeset), ctx)))
          .async
          .via(updateMovie)
          .runWith(Sink.seq)
          .map { results =>
            val ctx =

            val errors = results.collect {
              case (Left(movie), ctx) => movie
            }

            val items = results.collect {
              case (Right(movie), ctx) => movie
            }





          }
      }


  override def softDeleteMovie[A]: FlowWithContext[(String, MovieChangeset), A, Either[CatalogError, Movie], A, NotUsed] = ???

  override def deleteSoftDeleted[A]: FlowWithContext[Unit, A, Either[CatalogError, Int], A, NotUsed] = ???
}
