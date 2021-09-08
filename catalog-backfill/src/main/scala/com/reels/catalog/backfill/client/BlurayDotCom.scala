package com.reels.catalog.backfill.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri

import scala.concurrent.ExecutionContext



class BlurayDotCom(val baseUri: Uri)(implicit as: ActorSystem[_], ec: ExecutionContext) extends SprayJsonSupport {

}
