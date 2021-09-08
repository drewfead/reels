package com.reels.dynamodb

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.{AttributeAction, AttributeValue, AttributeValueUpdate}

import scala.jdk.CollectionConverters._

trait DynamoAST {
  sealed trait Node

  sealed trait Field extends Node {
    def toAttributeValue: AttributeValue
  }

  case object NotPresent extends Node

  case object ExplicitNullField extends Field {
    override def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .nul(true)
      .build()
  }

  case class StringField(value: String) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .s(value)
      .build()
  }

  case class StringSeqField(value: Seq[String]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .ss(value.asJava)
      .build()
  }

  case class BooleanField(value: Boolean) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .bool(value)
      .build()
  }

  case class BytesField(value: Array[Byte]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .b(SdkBytes.fromByteArray(value))
      .build()
  }

  case class BytesSeqField(value: Seq[Array[Byte]]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .bs(value.map(SdkBytes.fromByteArray).asJava)
      .build()
  }

  case class NumberField(value: String) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .n(value)
      .build()
  }

  case class NumberSeqField(value: Seq[String]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .ns(value.asJava)
      .build()
  }

  case class ObjectField(value: Map[String, Field]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .m(value.map { case (key, value) => (key, value.toAttributeValue)}.asJava)
      .build()
  }

  case class SeqField(value: Seq[Field]) extends Field {
    def toAttributeValue: AttributeValue = AttributeValue
      .builder()
      .l(value.map(_.toAttributeValue).asJava)
      .build()
  }
}

object DynamoAST extends DynamoAST



