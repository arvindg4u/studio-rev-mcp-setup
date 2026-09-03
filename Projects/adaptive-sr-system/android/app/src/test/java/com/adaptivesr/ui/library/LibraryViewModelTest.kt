package com.adaptivesr.ui.library

import com.adaptivesr.data.RaindropItemRef
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.ui.today.FakeRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeDrops(private val items: List<RaindropItem>) : RaindropSource {
  override suspend fun list(search: String): List<RaindropItem> = items
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
  @Test fun toggleOffKeepsCard_toggleOnDedupes() = runTest {
    val cards = mutableListOf(CardEntity(id = "c1", title = "R", raindropId = 42L))
    var lastToggle: Pair<Long, Boolean>? = null
    val repo = object : FakeRepo(cards) {
      override suspend fun setRaindropEnabled(item: RaindropItemRef, e: Boolean) { lastToggle = item.itemId to e }
    }
    val drops = FakeDrops(listOf(RaindropItem(42, "R", null, true)))
    val vm = LibraryViewModel(repo, drops, StandardTestDispatcher(testScheduler))
    vm.setSrTag(RaindropItem(42, "R", null, true), false)
    assertEquals(42L to false, lastToggle)
    assertEquals(1, cards.size)
    vm.setSrTag(RaindropItem(42, "R", null, true), true)
    assertEquals(42L to true, lastToggle)
    assertEquals(1, cards.size)
  }
}
