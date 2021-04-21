package com.reels.catalog.backfill

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import com.reels.catalog.backfill.api.Endpoints
import com.reels.catalog.backfill.client.{ReelsCatalog, TheMovieDatabase}
import com.reels.catalog.backfill.core.Backfill

import scala.concurrent.ExecutionContext

object Server {
  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      implicit val as: ActorSystem[_] = context.system
      implicit val ec: ExecutionContext = context.executionContext

      val catalog = new ReelsCatalog(
        Uri(s"${System.getenv("CATALOG_URL")}/catalog/movies/v1"),
      )

      val tmdb = new TheMovieDatabase(
        Uri(s"${System.getenv("TMDB_URL")}/4"),
        System.getenv("TMDB_AUTH_TOKEN"),
      )

      val backfill = new Backfill(catalog, tmdb, None)

      val endpoints = new Endpoints(backfill)

      import akka.http.scaladsl.server.Directives._
      Http()
        .newServerAt("localhost", 8888)
        .bind(endpoints.routes.reduce[Route](_ ~ _))

      Behaviors.empty
    }

    val system = ActorSystem[Nothing](rootBehavior, "CatalogBackfill")
  }
}
