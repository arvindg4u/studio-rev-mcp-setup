package com.adaptivesr.ui.stats

import androidx.lifecycle.ViewModel
import com.adaptivesr.data.SrRepository
import com.adaptivesr.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StatsViewModel @Inject constructor(
  repo: SrRepository,
  @ApplicationScope io: CoroutineContext
) : ViewModel() {
  // Own scope (same Task 3 idiom as TodayViewModel) so unit tests can inject
  // a TestDispatcher; production default is Dispatchers.IO via Hilt.
  private val scope = CoroutineScope(SupervisorJob() + io)

  val uiState: StateFlow<StatsUi> =
    repo.stats().stateIn(scope, SharingStarted.Eagerly, StatsUi())

  override fun onCleared() {
    scope.cancel()
    super.onCleared()
  }
}
