package com.reels.catalog.backfill.core

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.util.FastFuture
import akka.stream.Attributes.logLevels
import akka.stream.scaladsl.{Flow, RetryFlow, Source}
import com.reels.catalog.backfill.client.TheMovieDatabaseRequest.{Discover, Get}
import com.reels.catalog.backfill.client.TheMovieDatabaseResponse.{Movie => TMDBMovie}
import com.reels.catalog.backfill.client.{ReelsCatalog, ReelsCatalogGenre, ReelsCatalogRequest, ReelsCatalogResponse, ReelsCatalogSpokenLanguage, TheMovieDatabase, TheMovieDatabaseRequest, UnexpectedStatusCode}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

case class BackfillParams(year: Int, count: Option[Long], offset: Option[Long])

case class Backfilled(backfilledCount: Long, noopCount: Long, errorCount: Long)

class Backfill(
  val catalog: ReelsCatalog,
  val tmdb: TheMovieDatabase,
  val maxRequestsPerSecond: Option[Int] = None,
)(
  implicit ec: ExecutionContext,
) {
  private val tmdbPageSize = 20

  private def discoverRequest(params: BackfillParams): Discover = {
    Discover(
      primaryReleaseYear = Some(params.year),
      page = params.offset.map {
        _ / tmdbPageSize
      }
    )
  }

  type Fallible[O, T] = Either[UnexpectedStatusCode[T], O]
  type TFallible[O, T] = (Fallible[O, T], T)

  private def paginateDiscovery[T]: Flow[(Discover, T), TFallible[TMDBMovie, T], NotUsed] =
    Flow[(Discover, T)].flatMapConcat {
      case (d, span) =>
        val p: Long = d.page.getOrElse(0L)
        Source.unfoldAsync(p -> (p + 1)) {
          case (page, totalPages) if page < totalPages =>
            tmdb.discoverSingle(d.copy(page = Some(page + 1)), span).map {
              case (Right(discoveries), span) =>
                Some((discoveries.page -> discoveries.totalPages, discoveries.results.map(Right(_)) -> span))
              case (Left(err), span) =>
                Some((page -> totalPages, Seq.fill(tmdbPageSize)(Left(err)) -> span))
            }
          case _ =>
            FastFuture.successful(None)
        }.expand {
          case (results, span) => results.iterator.map(_ -> span)
        }
    }

  private def convert[T]: Flow[TFallible[TMDBMovie, T], TFallible[ReelsCatalogRequest, T], NotUsed] =
    Flow[TFallible[TMDBMovie, T]].map {
      case (fallible, span) => fallible.map { movie => ReelsCatalogRequest(
        title = movie.title,
        tagline = movie.tagline,
        overview = Some(movie.overview),
        spokenLanguages = Seq(movie.originalLanguage.map { lang =>
            ReelsCatalogSpokenLanguage(
              code = lang,
              name = lang,
            )
        }).flatten,
        productionCountries = Seq(),
        genres = movie.genreIds.map { id =>
          ReelsCatalogGenre(
            id = UUID.nameUUIDFromBytes(id.toString.getBytes(StandardCharsets.UTF_8)).toString,
            name = id.toString
          )
        },
        releaseDate = movie.releaseDate,
        foreignUrl = Some(tmdb.uriFor(Get(movie.id)).toString()),
      )} -> span
    }

  private def summarize[T]: Flow[TFallible[ReelsCatalogResponse, T], (Backfilled, T), NotUsed] =
    Flow[TFallible[ReelsCatalogResponse, T]].map {
      case (Right(_), span) => Backfilled(1, 0, 0) -> span
      case (Left(UnexpectedStatusCode(StatusCodes.Conflict, _)), span) => Backfilled(0, 1, 0) -> span
      case (Left(_), span) => Backfilled(0, 0, 1) -> span
    }
    .conflate[(Backfilled, T)] {
      case ((b1, _), (b2, span2)) => Backfilled(
        b1.backfilledCount + b2.backfilledCount,
        b1.noopCount + b2.noopCount,
        b1.errorCount + b2.errorCount,
      ) -> span2
    }

  def source[T](params: BackfillParams, span: T): Source[(Backfilled, T), NotUsed] = {
    val base = Source.single(discoverRequest(params) -> span)
      .log("request_discovery")
      .withAttributes(
        logLevels(
          onElement = Logging.DebugLevel,
          onFinish = Logging.DebugLevel,
          onFailure = Logging.ErrorLevel)
      )
      .via(paginateDiscovery[T])
      .log("paginate_discovery_results")
      .withAttributes(
        logLevels(
          onElement = Logging.DebugLevel,
          onFinish = Logging.DebugLevel,
          onFailure = Logging.ErrorLevel)
      )
      .via(convert[T].async)

    val limited = params match {
      case BackfillParams(_, Some(count), _) => base.take(count)
      case _ => base
    }

    val offset = params match {
      case BackfillParams(_, _, Some(offset)) => limited.drop(offset % tmdbPageSize)
      case _ => limited
    }

    def failures[O] = offset.collect {
      case (Left(err), span) => Left[UnexpectedStatusCode[T], O](err) -> span
    }

    val successes = offset.collect {
      case (Right(success), span) => success -> span
    }

    val sentToCatalog = successes
      .via(catalog.flow[T].async)
      .log("request_catalog_creation")
      .withAttributes(
        logLevels(
          onElement = Logging.DebugLevel,
          onFinish = Logging.DebugLevel,
          onFailure = Logging.ErrorLevel)
      )

    val summarized = sentToCatalog.merge(failures)
      .via(summarize[T])
      .log("aggregate_catalog_results")
      .withAttributes(
        logLevels(
          onElement = Logging.InfoLevel,
          onFinish = Logging.DebugLevel,
          onFailure = Logging.ErrorLevel)
      )

    maxRequestsPerSecond match {
      case Some(rps) => summarized.throttle(rps, FiniteDuration.apply(1, TimeUnit.SECONDS))
      case None => summarized
    }
  }
}
