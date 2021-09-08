package com.reels.catalog.repo

import akka.NotUsed
import akka.stream.scaladsl.FlowWithContext
import com.reels.catalog.core.{CatalogError, Movie, MovieChangeset}
import com.reels.constructs.{Page, PageWithErrors}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

case class FindParameters(
  pageSize: Int,
  anchor: Option[String],
)

trait MovieRepo {
  def findMovies[A]: FlowWithContext[FindParameters, A, Either[CatalogError, Page[Movie]], A, NotUsed]
  def findOneMovie[A]: FlowWithContext[String, A, Either[CatalogError, Option[Movie]], A, NotUsed]
  def findMoviesWithIds[A]: FlowWithContext[Seq[String], A, Either[CatalogError, Page[Movie]], A, NotUsed]
  def findStaleIndexed[A]: FlowWithContext[FindParameters, A, Either[CatalogError, Page[Movie]], A, NotUsed]
  def createMovie[A]: FlowWithContext[Movie, A, Either[CatalogError, Movie], A, NotUsed]
  def updateMovie[A]: FlowWithContext[(String, MovieChangeset), A, Either[CatalogError, Movie], A, NotUsed]
  def updateMovies[A]: FlowWithContext[(Seq[String], MovieChangeset), A, PageWithErrors[CatalogError, Movie], A, NotUsed]
  def softDeleteMovie[A]: FlowWithContext[(String, MovieChangeset), A, Either[CatalogError, Movie], A, NotUsed]
  def deleteSoftDeleted[A]: FlowWithContext[(), A, Either[CatalogError, Int], A, NotUsed]
}

object MovieRepo {
  def dynamoDb(client: DynamoDbAsyncClient, parallelism: Int): MovieRepo = new DynamoDbMovieRepo(client, parallelism)
}
