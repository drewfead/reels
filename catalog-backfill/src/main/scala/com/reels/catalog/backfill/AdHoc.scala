package com.reels.catalog.backfill

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Sink
import com.reels.catalog.backfill.client.{ReelsCatalog, TheMovieDatabase}
import com.reels.catalog.backfill.core.{Backfill, BackfillParams}

import scala.concurrent.ExecutionContext

object AdHoc {
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

      val backfill = new Backfill(catalog, tmdb, Some(5))

      backfill
        .source(BackfillParams(2020, Some(25), None), NotUsed)
        .runWith(Sink.ignore)
        .onComplete(_ => as.terminate())

      Behaviors.empty
    }
    val system = ActorSystem[Nothing](rootBehavior, "CatalogBackfill")
  }
}
