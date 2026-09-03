package com.adaptivesr.ui.add

import com.adaptivesr.ui.today.FakeRepo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AddViewModelTest {
  @Test fun blankTitleRejected_offlineSaveQueued() = runTest {
    val added = mutableListOf<Pair<String, String?>>()
    val repo = object : FakeRepo(emptyList()) {
      override suspend fun enqueueAdd(t: String, l: String?) { added += t to l }
    }
    val vm = AddViewModel(repo)
    assertEquals(false, vm.save("  ", null))
    assertEquals(true, vm.save("Spaced idea", "https://x.test/1"))
    assertEquals(listOf("Spaced idea" to "https://x.test/1"), added)
  }
}
