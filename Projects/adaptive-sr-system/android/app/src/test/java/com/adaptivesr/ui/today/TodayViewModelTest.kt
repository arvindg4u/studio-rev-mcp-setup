package com.adaptivesr.ui.today

import app.cash.turbine.test
import com.adaptivesr.core.Rating
import com.adaptivesr.data.RaindropItemRef
import com.adaptivesr.data.SrRepository
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.ui.stats.StatsUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
  @Test fun emitsOverdueFirstAndMarksQueued() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val cards = listOf(
      CardEntity(id = "b", title = "B", dueAt = 200, pendingSync = 1),
      CardEntity(id = "a", title = "A", dueAt = 100)
    )
    var rated: Pair<String, Rating>? = null
    val repo = object : FakeRepo(cards) {
      override suspend fun rateCard(id: String, rating: Rating) { rated = id to rating }
    }
    val vm = TodayViewModel(repo, StandardTestDispatcher(testScheduler))
    vm.uiState.test {
      awaitItem() // stateIn initial value
      val s = awaitItem() // mapped queue
      assertEquals(listOf("a", "b"), s.items.map { it.card.id })
      assertEquals(1, s.pendingCount)
      assertTrue(s.items.first { it.card.id == "b" }.queued)
      assertTrue(s.items.first().previews[Rating.GOOD]!!.contains("d"))
      vm.rate("a", Rating.GOOD)
      advanceUntilIdle()
      assertEquals("a" to Rating.GOOD, rated)
      cancelAndIgnoreRemainingEvents()
    }
  }
}

// Public abstract so Task 4's AddViewModelTest (same test source set) can extend it.
abstract class FakeRepo(private val cards: List<CardEntity>) : SrRepository {
  override fun dueQueue() = MutableStateFlow(cards)
  override fun searchAll(q: String) = MutableStateFlow(cards)
  override fun stats() = MutableStateFlow(StatsUi())
  override suspend fun rateCard(id: String, rating: Rating) {}
  override suspend fun pullDue() {}
  override suspend fun flushPending() {}
  override suspend fun enqueueAdd(t: String, l: String?) {}
  override suspend fun enqueueRaindropIfAbsent(item: RaindropItemRef) {}
  override suspend fun setRaindropEnabled(item: RaindropItemRef, e: Boolean) {}
}
