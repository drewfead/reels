package com.reels.credits

import com.reels.credits.Credits.Credit

trait Credits[F[_]] {
  def create(): Credit
}

object Credits {
  case class Credit()
}
