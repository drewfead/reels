package com.reels.constructs

case class Page[T](
  nextAnchor: Option[String],
  items: Seq[T],
)
