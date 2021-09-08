package com.reels.catalog.idx

import akka.NotUsed
import akka.stream.scaladsl.FlowWithContext
import com.reels.catalog.core.{CatalogError, Movie, SearchQuery}
import com.reels.constructs.Page

case class SearchParameters(
  pageSize: Long,
  anchor: Option[String],
)

trait MovieIndex {
  def createIndex[A]: FlowWithContext[(), A, Either[CatalogError, Boolean], A, NotUsed]
  def indexMovies[A]: FlowWithContext[Seq[Movie], A, Either[CatalogError, Page[Movie]], A, NotUsed]
  def searchMovies[A]: FlowWithContext[(SearchQuery, SearchParameters), A, Either[CatalogError, Page[Movie]], A, NotUsed]
}

object MovieIndex {
  def unimplemented: MovieIndex = new MovieIndex {
    override def createIndex[A]: FlowWithContext[Unit, A, Either[CatalogError, Boolean], A, NotUsed] = ???

    override def indexMovies[A]: FlowWithContext[Seq[Movie], A, Either[CatalogError, Page[Movie]], A, NotUsed] = ???

    override def searchMovies[A]: FlowWithContext[(SearchQuery, SearchParameters), A, Either[CatalogError, Page[Movie]], A, NotUsed] = ???
  }
}
