package com.reels.catalog.backfill.api

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.reels.catalog.backfill.core.{Backfill, BackfillParams, Backfilled}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

case class BackfillJob(
  year: Int,
  count: Option[Long],
  offset: Option[Long],
) {
  def toParams: BackfillParams = {
    BackfillParams(
      year = year,
      count = count,
      offset = offset,
    )
  }
}

object BackfillProtocol extends DefaultJsonProtocol {
  implicit val backfillJobFormat: RootJsonFormat[BackfillJob] = jsonFormat3(BackfillJob.apply)
  implicit val backfilledFormat: RootJsonFormat[Backfilled] = jsonFormat3(Backfilled.apply)
}

class Endpoints(
  val backfill: Backfill,
)(
  implicit actorSystem: ActorSystem[_],
) extends SprayJsonSupport {
  import BackfillProtocol._

  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  val backfillJobs: Route = (path("catalog" / "backfill_jobs" / "v1") & post) {
      (withoutRequestTimeout & entity(as[BackfillJob])) { (job: BackfillJob) =>
        complete(backfill.source(job.toParams, NotUsed).map(_._1))
      }
  }

  val routes = Seq(backfillJobs)
}
