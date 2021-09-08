package com.reels.credits

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import com.reels.credits.People.{Person, PersonId}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.http4s.dsl.Http4sDsl

object CreditsRoutes {
  implicit val personEncoder: Encoder[Person] =
    deriveEncoder[Person]
  implicit def personEntityEncoder[F[_]: Applicative]: EntityEncoder[F, Person] =
    jsonEncoderOf(personEncoder)

  implicit val cParamDecoder: Decoder[People.CreateParameters] =
    deriveDecoder[People.CreateParameters]
  implicit def cParamEntityDecoder[F[_]: Sync]: EntityDecoder[F, People.CreateParameters] =
    jsonOf[F, People.CreateParameters]

  implicit val uParamDecoder: Decoder[People.UpdateParameters] =
    deriveDecoder[People.UpdateParameters]
  implicit def uParamEntityDecoder[F[_]: Sync]: EntityDecoder[F, People.UpdateParameters] =
    jsonOf[F, People.UpdateParameters]

  def peopleRoutes[F[_]: Sync](P: People[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "person" / id =>
        for {
          found <- P.findOne(PersonId(id))
          resp   = found match {
            case Left(_)        => InternalServerError
            case Right(None)    => NotFound
            case Right(Some(p)) => Ok(p)
          }
        } yield resp
      case req @ POST -> Root / "person" =>
        for {
          params  <- req.as[People.CreateParameters]
          created <- P.create(params)
          resp     = created match {
            case Left(_)  => InternalServerError
            case Right(p) => Ok(p)
          }
        } yield resp
      case req @ PUT -> Root / "person" / id =>
        for {
          params  <- req.as[People.UpdateParameters]
          updated <- P.update(PersonId(id), params)
          resp     = updated match {
            case Left(_)  => InternalServerError
            case Right(p) => Ok(p)
          }
        } yield resp
      case DELETE -> Root / "person" / id =>
        for {
          deleted <- P.delete(PersonId(id))
          resp     = deleted match {
            case Left(_)  => InternalServerError
            case Right(_) => NoContent
          }
        } yield resp
    }
  }

  def healthRoute[F[_]: Sync]: HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        Ok()
    }
  }
}