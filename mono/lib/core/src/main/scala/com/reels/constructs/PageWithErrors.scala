package com.reels.constructs

case class PageWithErrors[E, T](
  nextAnchor: Option[String],
  items: Seq[T],
  errors: Seq[E],
)
