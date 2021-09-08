package com.reels.catalog.api

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import com.reels.catalog.core.{Catalog, Country, CreateMovieParams, Genre, IdAlreadyExists, Language, Movie, SearchQuery, UpdateMovieParams}
import com.reels.constructs.{Page, PaginationParameters}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

object ApiProtocol {
  implicit val genreEncoder: Encoder[Genre] = deriveEncoder
  implicit val genreDecoder: Decoder[Genre] = deriveDecoder
  implicit val countryEncoder: Encoder[Country] = deriveEncoder
  implicit val countryDecoder: Decoder[Country] = deriveDecoder
  implicit val languageEncoder: Encoder[Language] = deriveEncoder
  implicit val languageDecoder: Decoder[Language] = deriveDecoder

  implicit val movieEncoder: Encoder[Movie] = deriveEncoder
  implicit val alreadyExistsEncoder: Encoder[IdAlreadyExists] = deriveEncoder
  implicit val createMovieParamsDecoder: Decoder[CreateMovieParams] = deriveDecoder
  implicit val updateMovieParamsDecoder: Decoder[UpdateMovieParams] = deriveDecoder

  case class ApiPage[T](
    items: Seq[T],
    nextPage: Option[Uri],
  )

  object ApiPage {
    def apply[T](
      page: Page[T],
      baseUri: Uri,
      count: Option[Int],
    ): ApiPage[T] = {
      ApiPage(
        items = page.items,
        nextPage = page.nextAnchor.map {
          nextAnchor =>
            baseUri.copy(
              path = Uri.Path("catalog/movies/v1"),
              rawQueryString = Seq(
                count.map(c => "count=$c"),
                s"anchor=$nextAnchor".some
              ).flatten.mkString("&").some
            )
        }
      )
    }
  }
}

class Endpoints(
   val catalog: Catalog,
   val baseUri: Uri,
)(
 implicit actorSystem: ActorSystem[_],
) extends FailFastCirceSupport {
  import ApiProtocol._

  val route: Route = pathPrefix("catalog" / "movies" / "v1") {
    concat(
      post {
        entity(as[CreateMovieParams]) {
          (params: CreateMovieParams) =>
            complete {
              catalog.createMovie(params, NotUsed).asSource.map {
                case (Right(movie), _) => StatusCodes.Created -> movie
                case (Left(err@IdAlreadyExists(_)), _) => StatusCodes.Conflict -> err
                case (Left(_), _) => StatusCodes.InternalServerError
              }.run()
            }
        }
      },
      get {
        parameters("count".as[Int].optional, "anchor".optional).as(PaginationParameters.apply _) {
          (pagination: PaginationParameters) =>
            concat(
              parameter("search_term").as(SearchQuery.apply _) {
                (search: SearchQuery) =>
                  complete {
                    catalog.searchMovies(search, pagination, NotUsed).asSource.map {
                      case (Right(page), _) => StatusCodes.OK -> ApiPage(page, baseUri, pagination.count)
                      case (Left(_), _) => StatusCodes.InternalServerError
                    }.run()
                  }
              },
              complete {
                catalog.findMovies(pagination, NotUsed).asSource.map {
                  case (Right(page), _) => StatusCodes.OK -> ApiPage(page, baseUri, pagination.count)
                  case (Left(_), _) => StatusCodes.InternalServerError
                }.run()
              }
            )
        }
      },
      path(Segment) {
        (id: String) =>
          concat(
            get {
              complete {
                catalog.findOneMovie(id, NotUsed).asSource.map {
                  case (Right(Some(movie)), _) => StatusCodes.OK -> movie
                  case (Right(None), _) => StatusCodes.NotFound
                  case (Left(_), _) => StatusCodes.InternalServerError
                }.run()
              }
            },
            put {
              entity(as[UpdateMovieParams]) {
                (params: UpdateMovieParams) =>
                  complete {
                    catalog.updateMovie(id, params, NotUsed).asSource.map {
                      case (Right(movie), _) => StatusCodes.OK -> movie
                      case (Left(_), _) => StatusCodes.InternalServerError
                    }.run()
                  }
              }
            },
            delete {
              complete {
                catalog.deleteMovie(id, NotUsed).asSource.map {
                  case(Right(_), _) => StatusCodes.NoContent
                  case(Left(_), _) => StatusCodes.InternalServerError
                }.run()
              }
            }
          )
      }
    )
  }

  val routes: Seq[Route] = Seq(route)
}
