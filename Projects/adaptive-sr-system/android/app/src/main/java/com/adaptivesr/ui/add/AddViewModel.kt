package com.adaptivesr.ui.add

import androidx.lifecycle.ViewModel
import com.adaptivesr.data.SrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AddUi(val text: String = "", val link: String? = null, val saved: Boolean = false, val error: String? = null)

@HiltViewModel
class AddViewModel @Inject constructor(private val repo: SrRepository) : ViewModel() {
  private val _ui = MutableStateFlow(AddUi())
  val uiState: StateFlow<AddUi> = _ui

  fun prefill(text: String) {
    _ui.value = _ui.value.copy(text = text)
  }

  suspend fun save(title: String, link: String?): Boolean {
    if (title.isBlank()) {
      _ui.value = _ui.value.copy(error = "Title required")
      return false
    }
    repo.enqueueAdd(title.trim(), link?.trim()?.ifBlank { null })
    _ui.value = _ui.value.copy(saved = true, error = null)
    return true
  }
}
