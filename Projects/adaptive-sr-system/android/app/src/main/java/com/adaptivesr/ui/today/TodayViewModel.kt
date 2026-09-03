package com.adaptivesr.ui.today

import com.adaptivesr.core.Rating
import com.adaptivesr.core.Sm2
import com.adaptivesr.data.SrRepository
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayRow(val card: CardEntity, val previews: Map<Rating, String>, val overdueDays: Int, val queued: Boolean)
data class TodayUiState(val items: List<TodayRow> = emptyList(), val pendingCount: Int = 0, val lastPull: Long? = null)

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val repo: SrRepository,
  @ApplicationScope io: CoroutineContext
) : androidx.lifecycle.ViewModel() {
  // Own scope (instead of viewModelScope on Dispatchers.Main) so unit tests can
  // inject a TestDispatcher; production default is Dispatchers.IO via Hilt.
  private val scope = CoroutineScope(SupervisorJob() + io)

  val uiState: StateFlow<TodayUiState> = repo.dueQueue().map { list ->
    val now = System.currentTimeMillis()
    // Overdue-first here, not just in Room: the repo interface guarantees no
    // ordering, so the ViewModel enforces the contract. Mirrors CardDao's
    // ORDER BY (null dueAt first, then dueAt ASC, then id ASC) exactly.
    val sorted = list.sortedWith(compareBy<CardEntity>({ it.dueAt != null }, { it.dueAt }, { it.id }))
    TodayUiState(sorted.map { c ->
      val od = c.dueAt?.let { maxOf(0, ((now - it) / 86_400_000).toInt()) } ?: 0
      TodayRow(c, Rating.entries.associateWith { r -> "${Sm2.preview(c.intervalDays, c.reviewCount, r)}d" }, od, c.pendingSync == 1)
    }, sorted.count { it.pendingSync == 1 })
  }.stateIn(scope, SharingStarted.Eagerly, TodayUiState())

  fun rate(id: String, r: Rating) {
    scope.launch { repo.rateCard(id, r); repo.flushPending() }
  }

  fun confirmMaster(id: String) = rate(id, Rating.MASTER)

  fun refresh() {
    scope.launch { repo.pullDue() }
  }

  override fun onCleared() {
    scope.cancel()
    super.onCleared()
  }
}
