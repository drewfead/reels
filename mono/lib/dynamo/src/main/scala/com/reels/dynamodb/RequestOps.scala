package com.reels.dynamodb

import java.util.{Map => JMap}

import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, BatchGetItemRequest, GetItemRequest, KeysAndAttributes, PutItemRequest, QueryRequest, ScanRequest, UpdateItemRequest}
import com.reels.dynamodb.DynamoAST._

import scala.jdk.CollectionConverters._

trait RequestOps {
  implicit class KeyLike(key: Iterable[(String, Field)]) {
    def toJKey: JMap[String, AttributeValue] = {
      key.map {
        case (k, v) => k -> v.toAttributeValue
      }.toMap.asJava
    }
  }

  implicit class ScanRequestOps(builder: ScanRequest.Builder) {
    def fromAnchor(anchor: Anchor): ScanRequest.Builder = {
      builder.exclusiveStartKey(anchor.raw)
    }

    def fromAnchorIfPresent(anchor: Option[Anchor]): ScanRequest.Builder = {
      anchor match {
        case Some(a) => this.fromAnchor(a)
        case None    => builder
      }
    }
  }

  implicit class QueryRequestOps(builder: QueryRequest.Builder) {
    def fromAnchor(anchor: Anchor): QueryRequest.Builder = {
      builder.exclusiveStartKey(anchor.raw)
    }

    def fromAnchorIfPresent(anchor: Option[Anchor]): QueryRequest.Builder = {
      anchor match {
        case Some(a) => this.fromAnchor(a)
        case None    => builder
      }
    }
  }

  implicit class GetItemRequestOps(builder: GetItemRequest.Builder) {
    def withKey(key: Map[String, Field]): GetItemRequest.Builder = {
      builder.key(key.toJKey)
    }
  }

  implicit class BatchGetItemRequestOps(builder: BatchGetItemRequest.Builder) {
    def withTableKeys(tableKeys: Map[String, Seq[Map[String, Field]]]): BatchGetItemRequest.Builder = {
      builder.requestItems(tableKeys.map {
        case (tableName, keys) =>
          tableName -> KeysAndAttributes.builder()
            .keys(keys.map(_.toJKey).asJava)
            .build()
      }.asJava)
    }

    def withKeys(tableName: String, keys: Seq[Map[String, Field]]): BatchGetItemRequest.Builder = {
      this.withTableKeys(Map(tableName -> keys))
    }
  }

  implicit class PutItemRequestOps(builder: PutItemRequest.Builder) {
    def withItemFrom[T: DynamoFormat](from: T): PutItemRequest.Builder = {
      builder.item(DynamoFormat.unsafeEncodeItem(from))
    }
  }

  implicit class UpdateItemRequestOps(builder: UpdateItemRequest.Builder) {
    def withKey[F <: Field](key: Map[String, F]): UpdateItemRequest.Builder = {
      builder.key(key.toJKey)
    }
  }
}

object RequestOps extends RequestOps
