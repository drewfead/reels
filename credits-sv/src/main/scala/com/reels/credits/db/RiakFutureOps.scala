package com.reels.credits.db

import cats.effect.{Sync, Timer}
import cats.syntax.all._
import com.basho.riak.client.core.RiakFuture

import scala.concurrent.duration.FiniteDuration

object RiakFutureOps {
  implicit class Ops[A, C](riakFuture: RiakFuture[A, C]) {
    def lift[F[_]](
      pollEvery: FiniteDuration,
    )(implicit
      s: Sync[F],
      t: Timer[F],
    ): F[(A, C)] = {
      def poll(riakFuture: RiakFuture[A, C]): F[(A, C)] = {
        s.delay(riakFuture.isDone).flatMap {
          case true => s.delay(riakFuture.get -> riakFuture.getQueryInfo)
          case _    => t.sleep(pollEvery) *> poll(riakFuture)
        }
      }

      poll(riakFuture)
    }
  }
}
