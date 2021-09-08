package com.reels.credits

import java.util.concurrent.TimeUnit

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, Resource, Timer}
import cats.implicits._
import com.basho.riak.client.api.RiakClient
import com.basho.riak.client.core.RiakCluster
import com.reels.credits.db.PeopleStore
import io.chrisdavenport.log4cats.{Logger => FLogger}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.middleware.Logger

import scala.concurrent.duration.FiniteDuration

object CreditsServer {

  def server[F[_]: FLogger : ConcurrentEffect: ContextShift](implicit T: Timer[F]): Resource[F, Server[F]] = {
    for {
      client <- EmberClientBuilder.default[F].build

      riakClient  = RiakClient.newClient(8087, "127.0.0.1")
      peopleStore = PeopleStore.riakKV(riakClient, FiniteDuration(100, TimeUnit.MILLISECONDS))
      peopleAlg   = People.impl[F](peopleStore)

      httpApp = (
        CreditsRoutes.healthRoute[F] <+>
          CreditsRoutes.peopleRoutes(peopleAlg)[F]
      ).orNotFound

      // With Middlewares in place
      requestsLogged = Logger.httpApp(logHeaders = true, logBody = true)(httpApp)

      server <- EmberServerBuilder.default[F]
        .withHost("0.0.0.0")
        .withPort(8080)
        .withHttpApp(requestsLogged)
        .build
    } yield server
  }
}
