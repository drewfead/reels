package com.reels.catalog.backfill.client

import akka.http.scaladsl.model.StatusCode

case class UnexpectedStatusCode[T](statusCode: StatusCode, span: T) extends RuntimeException
