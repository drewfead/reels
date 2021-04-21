package com.reels.catalog.backfill.client

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, RequestEntity, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Sink, Source}
import spray.json.{DefaultJsonProtocol, JsValue, JsonFormat, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ReelsCatalogSpokenLanguage(
  code: String,
  name: String,
)

case class ReelsCatalogProductionCountry(
  code: String,
  name: String,
)

case class ReelsCatalogGenre(
  id: String,
  name: String,
)

case class ReelsCatalogRequest(
  title: String,
  tagline: Option[String],
  overview: Option[String],
  spokenLanguages: Seq[ReelsCatalogSpokenLanguage],
  productionCountries: Seq[ReelsCatalogProductionCountry],
  genres: Seq[ReelsCatalogGenre],
  releaseDate: Option[LocalDate],
  foreignUrl: Option[String],
)

case class ReelsCatalogResponse(
  id: String,
  tagline: Option[String],
  overview: Option[String],
  spokenLanguages: Seq[ReelsCatalogSpokenLanguage],
  productionCountries: Seq[ReelsCatalogProductionCountry],
  genres: Seq[ReelsCatalogGenre],
  releaseDate: Option[LocalDate],
  created: Instant,
  updated: Instant,
  foreignUrl: Option[String],
)

object ReelsCatalogProtocol extends DefaultJsonProtocol {
  implicit val localDateFormat: JsonFormat[LocalDate] = new JsonFormat[LocalDate] {
    override def write(obj: LocalDate): JsValue =
      implicitly[JsonFormat[String]].write(DateTimeFormatter.ISO_LOCAL_DATE.format(obj))

    override def read(json: JsValue): LocalDate =
      LocalDate.from(DateTimeFormatter.ISO_LOCAL_DATE.parse(implicitly[JsonFormat[String]].read(json)))
  }

  implicit val instantFormat: JsonFormat[Instant] = new JsonFormat[Instant] {
    override def write(obj: Instant): JsValue =
      implicitly[JsonFormat[String]].write(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(obj, ZoneOffset.UTC))
      )

    override def read(json: JsValue): Instant =
      LocalDateTime.from(DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(
        implicitly[JsonFormat[String]].read(json)
      )).toInstant(ZoneOffset.UTC)
  }

  implicit val languageFormat: RootJsonFormat[ReelsCatalogSpokenLanguage] = jsonFormat2(ReelsCatalogSpokenLanguage.apply)
  implicit val countryFormat: RootJsonFormat[ReelsCatalogProductionCountry] = jsonFormat2(ReelsCatalogProductionCountry.apply)
  implicit val genreFormat: RootJsonFormat[ReelsCatalogGenre] = jsonFormat2(ReelsCatalogGenre.apply)
  implicit val requestFormat: RootJsonFormat[ReelsCatalogRequest] = jsonFormat8(ReelsCatalogRequest.apply)
  implicit val responseFormat: RootJsonFormat[ReelsCatalogResponse] = jsonFormat10(ReelsCatalogResponse.apply)
}

class ReelsCatalog(val baseUri: Uri)(implicit as: ActorSystem[_], ec: ExecutionContext) extends SprayJsonSupport {
  import ReelsCatalogProtocol._

  private def buildRequest[T]: Flow[(ReelsCatalogRequest, T), (HttpRequest, T), NotUsed] =
    Flow[(ReelsCatalogRequest, T)].flatMapConcat {
      case (from, span) =>
        Source.future(Marshal(from).to[RequestEntity].map { entity =>
          HttpRequest(
            method = HttpMethods.POST,
            uri = baseUri,
            entity = entity
          )})
          .map(_ -> span)
    }

  type Result[T] = Either[UnexpectedStatusCode[T], ReelsCatalogResponse]

  private def parseResponse[T]: Flow[(Try[HttpResponse], T), (Result[T], T), NotUsed] =
    Flow[(Try[HttpResponse], T)].flatMapConcat {
      case (Success(response), span) => response.status match {
        case StatusCodes.CREATED => Source.future(Unmarshal(response.entity).to[ReelsCatalogResponse])
          .map(s => Right[UnexpectedStatusCode[T], ReelsCatalogResponse](s) -> span)
        case status =>
          Source.single(Left[UnexpectedStatusCode[T], ReelsCatalogResponse](UnexpectedStatusCode(status, span)) -> span)
      }
      case (Failure(err), _) => Source.failed(err)
    }

  private def requestFlow[T]: Flow[(ReelsCatalogRequest, T), (Result[T], T), NotUsed] =
    Flow[(ReelsCatalogRequest, T)]
      .via(buildRequest)
      .via(Http().superPool())
      .via(parseResponse)

  def single[T](
    request: ReelsCatalogRequest,
    span: T,
  ): Future[(Result[T], T)] =
    Source.single(request -> span)
      .via(requestFlow)
      .runWith(Sink.head)

  def flow[T]: Flow[(ReelsCatalogRequest, T), (Result[T], T), NotUsed] =
    requestFlow
}
