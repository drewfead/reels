package com.reels.credits

import cats.effect.{ExitCode, IO, IOApp, Sync}
import cats.syntax.all._
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

    CreditsServer.server[IO]
      .use(server =>
        IO.delay(println(s"Server Has Started at ${server.address}")) >>
          IO.never.as(ExitCode.Success)
      )
  }
}
