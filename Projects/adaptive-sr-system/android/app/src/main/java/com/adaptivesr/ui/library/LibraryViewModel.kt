package com.adaptivesr.ui.library

import androidx.lifecycle.ViewModel
import com.adaptivesr.data.RaindropItemRef
import com.adaptivesr.data.SrRepository
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.data.remote.RetrofitRaindropSource
import com.adaptivesr.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class RaindropItem(val id: Long, val title: String, val link: String?, val srEnabled: Boolean)

interface RaindropSource {
  suspend fun list(search: String): List<RaindropItem>
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
  private val repo: SrRepository,
  private val drops: RaindropSource,
  @ApplicationScope io: CoroutineContext
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + io)

  private val _allTab = MutableStateFlow<List<CardEntity>>(emptyList())
  val allTab: StateFlow<List<CardEntity>> = _allTab

  private val _raindropTab = MutableStateFlow<List<RaindropItem>>(emptyList())
  val raindropTab: StateFlow<List<RaindropItem>> = _raindropTab

  fun search(q: String) {
    scope.launch {
      _allTab.value = repo.searchAll(q).first()
      _raindropTab.value = drops.list(q)
    }
  }

  suspend fun setSrTag(item: RaindropItem, enabled: Boolean) {
    // Maps 1:1 to the repo carrier, then delegates; never deletes.
    repo.setRaindropEnabled(RaindropItemRef(item.id, item.title, item.link), enabled)
    _raindropTab.value = _raindropTab.value.map {
      if (it.id == item.id) it.copy(srEnabled = enabled) else it
    }
  }

  override fun onCleared() {
    scope.cancel()
    super.onCleared()
  }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RaindropSourceModule {
  @Binds
  @Singleton
  abstract fun bindRaindropSource(impl: RetrofitRaindropSource): RaindropSource
}
