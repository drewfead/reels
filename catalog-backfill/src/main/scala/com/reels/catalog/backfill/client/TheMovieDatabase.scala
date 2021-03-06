package com.reels.catalog.backfill.client

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri, headers}
import akka.http.scaladsl.unmarshalling._
import akka.stream.scaladsl.{Flow, RetryFlow, Sink, Source}
import com.reels.catalog.backfill.client.TheMovieDatabaseRequest.{Discover, Get}
import com.reels.catalog.backfill.client.TheMovieDatabaseResponse.{Discoveries, Movie}
import spray.json.{DefaultJsonProtocol, JsValue, JsonFormat, RootJsonFormat}

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

sealed trait TheMovieDatabaseResponse

object TheMovieDatabaseResponse {
  case class Movie(
    id: Long,
    title: String,
    overview: String,
    originalLanguage: Option[String] = None,
    tagline: Option[String] = None,
    releaseDate: Option[LocalDate] = None,
    genreIds: Seq[Long] = Seq(),
  ) extends TheMovieDatabaseResponse

  case class Discoveries(
    page: Long,
    results: Seq[Movie],
    totalPages: Long,
    totalResults: Long,
  ) extends TheMovieDatabaseResponse
}

object TheMovieDatabaseProtocol extends SnakeCaseJsonProtocol {
  implicit val date: JsonFormat[LocalDate] = new JsonFormat[LocalDate] {
    override def write(obj: LocalDate): JsValue =
      implicitly[JsonFormat[String]].write(DateTimeFormatter.ISO_LOCAL_DATE.format(obj))

    override def read(json: JsValue): LocalDate =
      LocalDate.from(DateTimeFormatter.ISO_LOCAL_DATE.parse(implicitly[JsonFormat[String]].read(json)))
  }

  implicit val movie: RootJsonFormat[TheMovieDatabaseResponse.Movie] =
    jsonFormat7(TheMovieDatabaseResponse.Movie.apply)

  implicit val discoveries: RootJsonFormat[TheMovieDatabaseResponse.Discoveries] =
    jsonFormat4(TheMovieDatabaseResponse.Discoveries.apply)
}

sealed trait TheMovieDatabaseRequest

object TheMovieDatabaseRequest extends DefaultJsonProtocol {
  case class Discover(
    releaseDateGTE: Option[LocalDate] = None,
    releaseDateLTE: Option[LocalDate] = None,
    primaryReleaseYear: Option[Int] = None,
    withGenres: Option[Seq[Long]] = None,
    withoutGenres: Option[Seq[Long]] = None,
    page: Option[Long] = None,
  ) extends TheMovieDatabaseRequest

  case class Get(
    id: Long,
  ) extends TheMovieDatabaseRequest
}

class TheMovieDatabase(
  val baseUri: Uri,
  val authToken: String,
)(implicit as: ActorSystem[_]) extends SprayJsonSupport {
  import TheMovieDatabaseProtocol._

  def uriFor(request: TheMovieDatabaseRequest): Uri = {
    request match {
      case Discover(releaseDateGTE, releaseDateLTE, year, withGenres, withoutGenres, page) =>
        val qParams = Seq(
          releaseDateGTE.map("primary_release_date.gte" -> DateTimeFormatter.ISO_LOCAL_DATE.format(_)),
          releaseDateLTE.map("primary_release_date.lte" -> DateTimeFormatter.ISO_LOCAL_DATE.format(_)),
          year.map("primary_release_year" -> String.valueOf(_)),
          withGenres.map("with_genres" -> _.map(String.valueOf).mkString(",")),
          withoutGenres.map("without_genres" -> _.map(String.valueOf).mkString(",")),
          page.map("page" -> String.valueOf(_))
        ).flatten

        baseUri.copy(
          path = baseUri.path / "discover" / "movie",
          rawQueryString = qParams.map {
            case (key, value) => s"$key=$value"
          } match {
            case Nil => None
            case params => Some(params.mkString("&"))
          })

      case Get(id) =>
        baseUri.copy(
          path = baseUri.path / "movie" / String.valueOf(id)
        )
    }
  }

  type Req = TheMovieDatabaseRequest

  private def buildRequest[T]: Flow[(Req, T), (HttpRequest, T), NotUsed] =
    Flow[(TheMovieDatabaseRequest, T)].map {
      case (req, span) => HttpRequest(
        method = HttpMethods.GET,
        uri = uriFor(req),
        headers = immutable.Seq(
          headers.Authorization(OAuth2BearerToken(authToken)),
        )
      ) -> span
    }

  type Call = Try[HttpResponse]
  type TMDBR = TheMovieDatabaseResponse
  type Err[T] = UnexpectedStatusCode[T]
  type Res[O, T] = Either[Err[T], O]

  private def parseResponse[O <: TMDBR : RootJsonFormat, T]: Flow[(Call, T), (Res[O, T], T), NotUsed] =
    Flow[(Try[HttpResponse], T)].flatMapConcat {
      case (Success(res), span) => res.status match {
        case StatusCodes.OK => Source.future(Unmarshal(res.entity).to[O]).map(o => Right[Err[T], O](o) -> span)
        case status => Source.single(Left[Err[T], O](UnexpectedStatusCode(status, span)) -> span)
      }
      case (Failure(err), _) => Source.failed(err)
    }

  private def requestFlow[O <: TMDBR : RootJsonFormat, T]: Flow[(Req, T), (Res[O, T], T), NotUsed] =
    Flow[(TheMovieDatabaseRequest, T)]
      .via(buildRequest)
      .via(Http().superPool())
      .via(parseResponse[O, T])

  private def single[O <: TMDBR : RootJsonFormat, T](in: Req, span: T): Future[(Res[O, T], T)] =
    Source.single(in -> span)
      .via(requestFlow[O, T])
      .runWith(Sink.head)

  def discoverSingle[T](in: Discover, span: T): Future[(Res[Discoveries, T], T)] =
    single[Discoveries, T](in, span)

  def movieSingle[T](in: Get, span: T): Future[(Res[Movie, T], T)] =
    single[Movie, T](in, span)

  private val retryStatuses = Seq(
    StatusCodes.InternalServerError,
    StatusCodes.BadGateway,
    StatusCodes.RequestTimeout,
    StatusCodes.GatewayTimeout,
    StatusCodes.ServiceUnavailable,
    StatusCodes.NetworkConnectTimeout,
    StatusCodes.NetworkReadTimeout,
  )

  def flow[O <: TMDBR : RootJsonFormat, T]: Flow[(Req, T), (Res[O, T], T), NotUsed] =
    RetryFlow.withBackoff(
      minBackoff = FiniteDuration(10, TimeUnit.MILLISECONDS),
      maxBackoff = FiniteDuration(250, TimeUnit.MILLISECONDS),
      randomFactor = 0d,
      flow = requestFlow[O, T],
      maxRetries = 3
    ) {
      case ((request, span), (Left(UnexpectedStatusCode(status, _)), _)) if retryStatuses.contains(status) =>
        Some(request -> span)
      case _ =>
        None
    }

  def discoverFlow[T]: Flow[(Discover, T), (Res[Discoveries, T], T), NotUsed] =
    flow[Discoveries, T]

  def movieFlow[T]: Flow[(Get, T), (Res[Movie, T], T), NotUsed] =
    flow[Movie, T]
}
