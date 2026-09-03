package com.adaptivesr.core

enum class Rating { MASTER, EASY, GOOD, HARD, RELEARN }

object Sm2 {
  fun preview(prev: Int, n: Int, rating: Rating): Int = when (rating) {
    Rating.MASTER -> 0
    Rating.RELEARN -> 1
    Rating.EASY -> if (n == 0) 4 else Math.round(prev * 2.5f).toInt()
    Rating.GOOD -> if (n == 0) 2 else Math.round(prev * 2.0f).toInt()
    Rating.HARD -> if (n == 0) 1 else Math.round(prev * 1.2f).toInt()
  }.let { if (rating != Rating.MASTER && rating != Rating.RELEARN && n != 0) maxOf(it, 1) else it }
}
// Intentional divergence from SQL recorded here: spec SQL `sm2_next_interval(0, n>0, ...)`
// yields round(0*mult)=0, while preview() clamps to maxOf(it,1)=1. Preview is display-only;
// the server RPC is authoritative on flush, so a 0-vs-1 preview gap never persists.
