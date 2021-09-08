package com.reels.dynamodb

import java.util.{Collections, List => JList, Map => JMap}

import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, BatchGetItemResponse, GetItemResponse, PutItemResponse, QueryResponse, ScanResponse, UpdateItemResponse}
import cats.syntax.all._
import com.reels.dynamodb.DynamoFormat.{AggregateError, Error}

import scala.jdk.CollectionConverters._

trait ResponseOps {
  private def parseItems[T: DynamoFormat](
    items: JList[JMap[String, AttributeValue]],
  ): Either[DynamoFormat.Error, Seq[T]] = {
    items
      .asScala
      .map(DynamoFormat.decodeItem)
      .foldLeft(Seq[T]().asRight[Error]) {
        case (Right(_), Left(err))                      => err.asLeft
        case (Left(err), Right(_))                      => err.asLeft
        case (Left(agg @ AggregateError(_)), Left(err)) => agg.appended(err).asLeft
        case (Left(err1), Left(err2))                   => AggregateError(Seq(err1, err2)).asLeft
        case (Right(decoded), Right(field))             => decoded.appended(field).asRight
      }
  }

  implicit class ScanResponseOps(response: ScanResponse) {
    def itemsAs[T: DynamoFormat]: Either[DynamoFormat.Error, Seq[T]] = {
      parseItems(response.items())
    }

    def nextAnchor: Option[Anchor] = {
      if(response.hasLastEvaluatedKey) {
        Anchor(response.lastEvaluatedKey()).some
      } else {
        None
      }
    }
  }

  implicit class QueryResponseOps(response: QueryResponse) {
    def itemsAs[T: DynamoFormat]: Either[DynamoFormat.Error, Seq[T]] = {
      parseItems(response.items())
    }

    def nextAnchor: Option[Anchor] = {
      if(response.hasLastEvaluatedKey) {
        Anchor(response.lastEvaluatedKey()).some
      } else {
        None
      }
    }
  }

  implicit class GetItemResponseOps(response: GetItemResponse) {
    def itemAs[T: DynamoFormat]: Either[DynamoFormat.Error, Option[T]] = {
      if(response.hasItem) {
        DynamoFormat.decodeItem[T](response.item()).map(_.some)
      } else {
        None.asRight
      }
    }
  }

  implicit class BatchGetItemResponseOps(response: BatchGetItemResponse) {
    def itemsFromTableAs[T: DynamoFormat](tableName: String): Either[DynamoFormat.Error, Seq[T]] = {
      parseItems(response.responses().asScala.getOrElse(tableName, Collections.emptyList))
    }
  }

  implicit class PutItemResponseOps(response: PutItemResponse) {
    def oldItemAs[T: DynamoFormat]: Either[DynamoFormat.Error, Option[T]] = {
      if(response.hasAttributes) {
        DynamoFormat.decodeItem[T](response.attributes()).map(_.some)
      } else {
        None.asRight
      }
    }
  }

  implicit class UpdateItemResponseOps(response: UpdateItemResponse) {
    def itemAs[T: DynamoFormat]: Either[DynamoFormat.Error, Option[T]] = {
      if(response.hasAttributes) {
        DynamoFormat.decodeItem[T](response.attributes()).map(_.some)
      } else {
        None.asRight
      }
    }
  }
}

object ResponseOps extends ResponseOps
