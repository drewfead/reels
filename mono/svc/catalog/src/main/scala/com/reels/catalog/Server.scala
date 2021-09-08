package com.reels.catalog

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.reels.catalog.api.Endpoints
import com.reels.catalog.core.Catalog
import com.reels.catalog.idx.MovieIndex
import com.reels.catalog.repo.MovieRepo
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

object Server extends App {
  val rootBehavior = Behaviors.setup[Nothing] { context =>
    implicit val as: ActorSystem[_] = context.system
//    implicit val ec: ExecutionContext = context.executionContext

    val dynamoDb: DynamoDbAsyncClient = DynamoDbAsyncClient
      .builder()
      .httpClient(AkkaHttpClient.builder().withActorSystem(as).build())
      .build()

    CoordinatedShutdown(as)
      .addJvmShutdownHook(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, () => dynamoDb.close())

    val repo = MovieRepo.dynamoDb(dynamoDb, 1)
    val idx = MovieIndex.unimplemented
    val catalog = new Catalog(repo, idx)

    val endpoints = new Endpoints(catalog, Uri("http://localhost:8888"))

    import akka.http.scaladsl.server.Directives._
    Http()
      .newServerAt("localhost", 8888)
      .bind(endpoints.routes.reduce[Route](_ ~ _))

    Behaviors.empty
  }

  val system = ActorSystem[Nothing](rootBehavior, "CatalogServer")
}
