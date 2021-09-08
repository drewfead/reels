package com.reels.dynamodb

import java.util.{Collections, Map => JMap}

import cats.syntax.all._
import com.reels.dynamodb.DynamoAST.{BooleanField, BytesField, BytesSeqField, ExplicitNullField, Field, Node, NotPresent, NumberField, NumberSeqField, ObjectField, SeqField, StringField, StringSeqField}
import io.circe.DecodingFailure
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.jdk.CollectionConverters._

trait DynamoFormat[T] {
  def encode(obj: T): DynamoAST.Node
  def decode(node: DynamoAST.Node): Either[DynamoFormat.Error, T]
}

object DynamoFormat {
  sealed trait Error extends Exception

  case class InvalidRootField(field: Field) extends Error

  case class UnsupportedFieldType(av: AttributeValue) extends Error

  case object TriedToParseNotPresent extends Error

  case object NoJsonBytesRepresentation extends Error

  case class CirceDecodingFailure(error: DecodingFailure) extends Error

  case class AggregateError(errors: Seq[Error]) extends Error {
    def appended(error: Error): AggregateError = {
      AggregateError(this.errors.appended(error))
    }
  }

  def encodeItemFromNode(root: Node): Either[DynamoFormat.Error, JMap[String, AttributeValue]] = {
    root match {
      case NotPresent => Right(Collections.emptyMap())
      case obj: ObjectField => Right(obj.toAttributeValue.m())
      case field: Field => Left(DynamoFormat.InvalidRootField(field))
    }
  }

  private def uncheckedDecodeAsSeq(av: AttributeValue): Either[DynamoFormat.Error, Field] = {
    av.l().asScala
      .map(decodeFieldFromAv)
      .foldLeft(Seq[Field]().asRight[Error]) {
        case (Right(_), Left(err))                      => err.asLeft
        case (Left(err), Right(_))                      => err.asLeft
        case (Left(agg @ AggregateError(_)), Left(err)) => agg.appended(err).asLeft
        case (Left(err1), Left(err2))                   => AggregateError(Seq(err1, err2)).asLeft
        case (Right(decoded), Right(field))             => decoded.appended(field).asRight
      }
      .map(SeqField.apply)
  }

  private def uncheckedDecodeAsObj(av: AttributeValue): Either[DynamoFormat.Error, Field] = {
    av.m().asScala
      .map { case (k, v) => (k, decodeFieldFromAv(v)) }
      .foldLeft(Map[String, Field]().asRight[Error]) {
        case (Right(_), (_, Left(err)))                      => err.asLeft
        case (Left(err), (_, Right(_)))                      => err.asLeft
        case (Left(agg @ AggregateError(_)), (_, Left(err))) => agg.appended(err).asLeft
        case (Left(err1), (_, Left(err2)))                   => AggregateError(Seq(err1, err2)).asLeft
        case (Right(decoded), (k, Right(field)))             => decoded.concat(Map(k -> field)).asRight[Error]
      }
      .map(ObjectField.apply)
  }

  private def decodeFieldFromAv(av: AttributeValue): Either[DynamoFormat.Error, Field] = av match {
    case _ if av.nul() != null     => ExplicitNullField.asRight
    case bool if av.bool() != null => BooleanField(bool.bool()).asRight
    case str if av.s() != null     => StringField(str.s()).asRight
    case num if av.n() != null     => NumberField(num.n()).asRight
    case byt if av.b() != null     => BytesField(byt.b().asByteArray()).asRight
    case sSeq if av.hasSs          => StringSeqField(sSeq.ss().asScala.toSeq).asRight
    case nSeq if av.hasNs          => NumberSeqField(nSeq.ns().asScala.toSeq).asRight
    case bSeq if av.hasBs          => BytesSeqField(bSeq.bs().asScala.map(_.asByteArray()).toSeq).asRight
    case flds if av.hasL           => uncheckedDecodeAsSeq(flds)
    case obj if av.hasM            => uncheckedDecodeAsObj(obj)
    case unsupported               => UnsupportedFieldType(unsupported).asLeft
  }

  def decodeNodeFromItem(item: JMap[String, AttributeValue]): Either[DynamoFormat.Error, Node] = {
    if(item.isEmpty) {
      NotPresent.asRight
    } else {
      uncheckedDecodeAsObj(AttributeValue.builder().m(item).build())
    }
  }

  def encode[T](
    obj: T,
  )(implicit
    dynamoFormat: DynamoFormat[T],
  ): Option[AttributeValue] = {
    dynamoFormat.encode(obj) match {
      case NotPresent => None
      case f: Field => Some(f.toAttributeValue)
    }
  }

  def encodeItem[T](
     obj: T,
   )(implicit
     dynamoFormat: DynamoFormat[T],
  ): Either[DynamoFormat.Error, JMap[String, AttributeValue]] = {
    encodeItemFromNode(dynamoFormat.encode(obj))
  }

  def unsafeEncodeItem[T](
    obj: T,
  )(implicit
    dynamoFormat: DynamoFormat[T],
  ): JMap[String, AttributeValue] = {
    encodeItemFromNode(dynamoFormat.encode(obj)).fold(throw _, identity)
  }

  def decode[T](
     attributeValue: AttributeValue,
   )(implicit
     dynamoFormat: DynamoFormat[T],
   ): Either[DynamoFormat.Error, T] = {
    decodeFieldFromAv(attributeValue)
      .flatMap(dynamoFormat.decode)
  }

  def decodeItem[T](
    item: JMap[String, AttributeValue],
  )(implicit
    dynamoFormat: DynamoFormat[T],
  ): Either[DynamoFormat.Error, T] = {
    decodeNodeFromItem(item).flatMap(node => dynamoFormat.decode(node))
  }
}
