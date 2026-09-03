package com.adaptivesr.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Sm2Test {
  @Test fun truthTable() {
    assertEquals(0, Sm2.preview(10, 3, Rating.MASTER))
    assertEquals(1, Sm2.preview(10, 3, Rating.RELEARN))
    assertEquals(4, Sm2.preview(0, 0, Rating.EASY))
    assertEquals(2, Sm2.preview(0, 0, Rating.GOOD))
    assertEquals(1, Sm2.preview(0, 0, Rating.HARD))
    assertEquals(25, Sm2.preview(10, 2, Rating.EASY))
    assertEquals(20, Sm2.preview(10, 2, Rating.GOOD))
    assertEquals(12, Sm2.preview(10, 2, Rating.HARD))
  }
}
