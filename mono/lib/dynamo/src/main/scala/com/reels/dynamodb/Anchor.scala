package com.reels.dynamodb

import java.nio.charset.StandardCharsets
import java.util.{Map => JMap}

import io.bullet.borer.Cbor
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

case class Anchor(val encoded: String, val raw: JMap[String, AttributeValue])

object Anchor {
  import io.bullet.borer.compat.circe._
  implicit val keyEncoder: Encoder[JMap[String, AttributeValue]] = deriveEncoder
  implicit val keyDecoder: Decoder[JMap[String, AttributeValue]] = deriveDecoder

  def apply(raw: JMap[String, AttributeValue]): Anchor = {
    new Anchor(new String(Cbor.encode(raw).toByteArray, StandardCharsets.UTF_8), raw)
  }

  def unapply(encoded: String): Option[Anchor] = {
    Cbor.decode(encoded.getBytes(StandardCharsets.UTF_8))
      .to[JMap[String, AttributeValue]]
      .valueEither.map(raw => new Anchor(encoded, raw))
      .toOption
  }
}