package com.reels.dynamodb

import cats.syntax.all._
import com.reels.dynamodb.DynamoAST._
import com.reels.dynamodb.DynamoFormat.{AggregateError, CirceDecodingFailure, Error, NoJsonBytesRepresentation, TriedToParseNotPresent}
import io.circe.{Decoder, Encoder, Json, JsonNumber, JsonObject}

trait CirceSupport {
  implicit def dynamoFormatFromEncoderAndDecoder[T](implicit
    encoder: Encoder[T],
    decoder: Decoder[T],
  ): DynamoFormat[T] = new DynamoFormat[T] {

    override def encode(obj: T): DynamoAST.Node =
      CirceJsonDynamoFormat.encode(encoder(obj))

    override def decode(node: DynamoAST.Node): Either[DynamoFormat.Error, T] =
      CirceJsonDynamoFormat.decode(node)
        .flatMap(json => decoder.decodeJson(json) match {
          case Right(t) => t.asRight
          case Left(failure) => CirceDecodingFailure(failure).asLeft
        })
  }
}

object CirceJsonDynamoFormat extends DynamoFormat[Json] {
  private def simplestSeq(fields: Seq[Field]): Field = fields.foldLeft[Field](ExplicitNullField) {
    case (ExplicitNullField, StringField(s))  => StringSeqField(Seq(s))
    case (ExplicitNullField, NumberField(n))  => NumberSeqField(Seq(n))
    case (ExplicitNullField, BytesField(b))   => BytesSeqField(Seq(b))

    case (StringSeqField(ss), StringField(s)) => StringSeqField(ss.appended(s))
    case (NumberSeqField(ns), NumberField(n)) => NumberSeqField(ns.appended(n))
    case (BytesSeqField(bs), BytesField(b))   => BytesSeqField(bs.appended(b))

    case (StringSeqField(ss), otherField)     => SeqField(ss.map(StringField.apply).appended(otherField))
    case (NumberSeqField(ns), otherField)     => SeqField(ns.map(NumberField.apply).appended(otherField))
    case (BytesSeqField(bs), otherField)      => SeqField(bs.map(BytesField.apply).appended(otherField))

    case (SeqField(fields), otherField)       => SeqField(fields.appended(otherField))
    case (_, _)                               => ??? // unreachable
  }

  override def encode(obj: Json): DynamoAST.Node = obj.foldWith(new Json.Folder[DynamoAST.Node] {
    override def onNull: DynamoAST.Node = ExplicitNullField
    override def onBoolean(value: Boolean): DynamoAST.Node = BooleanField(value)
    override def onNumber(value: JsonNumber): DynamoAST.Node = NumberField(value.toString)
    override def onString(value: String): DynamoAST.Node = StringField(value)

    override def onArray(value: Vector[Json]): DynamoAST.Node =
      simplestSeq(value.map(encode).collect {
        case f: Field => f
      })

    override def onObject(value: JsonObject): DynamoAST.Node =
      ObjectField(value.toMap.map {
        case (k, v) => k -> encode(v)
      }.collect {
        case (k, field: Field) => k -> field
      })
  })

  override def decode(node: DynamoAST.Node): Either[DynamoFormat.Error, Json] = node match {
    case ExplicitNullField =>
      TriedToParseNotPresent.asLeft
    case NotPresent =>
      Json.Null.asRight
    case StringField(s) =>
      Json.JString(s).asRight
    case BooleanField(b) =>
      Json.JBoolean(b).asRight
    case NumberField(n) =>
      Json.JNumber(JsonNumber.fromIntegralStringUnsafe(n)).asRight
    case BytesField(_) =>
      NoJsonBytesRepresentation.asLeft
    case StringSeqField(ss) =>
      Json.JArray(ss.map(s => Json.JString(s)).toVector).asRight
    case NumberSeqField(ns) =>
      Json.JArray(ns.map(n => Json.JNumber(JsonNumber.fromIntegralStringUnsafe(n))).toVector).asRight
    case BytesSeqField(_) =>
      NoJsonBytesRepresentation.asLeft
    case SeqField(fs) =>
      fs.map(f => decode(f))
        .foldLeft(Vector[Json]().asRight[DynamoFormat.Error]) {
          case (Right(_), Left(err))                      => err.asLeft
          case (Left(err), Right(_))                      => err.asLeft
          case (Left(agg @ AggregateError(_)), Left(err)) => agg.appended(err).asLeft
          case (Left(err1), Left(err2))                   => AggregateError(Seq(err1, err2)).asLeft
          case (Right(decoded), Right(field))             => decoded.appended(field).asRight
        }
        .map(Json.JArray.apply)
    case ObjectField(obj) =>
      obj.map {
        case (k, field) => k -> decode(field)
      }
      .foldLeft(Map[String, Json]().asRight[Error]) {
        case (Right(_), (_, Left(err)))                      => err.asLeft
        case (Left(err), (_, Right(_)))                      => err.asLeft
        case (Left(agg @ AggregateError(_)), (_, Left(err))) => agg.appended(err).asLeft
        case (Left(err1), (_, Left(err2)))                   => AggregateError(Seq(err1, err2)).asLeft
        case (Right(decoded), (k, Right(field)))             => decoded.concat(Map(k -> field)).asRight[Error]
      }
      .map(m => Json.JObject(JsonObject.fromMap(m)))
  }
}

object CirceSupport extends CirceSupport
