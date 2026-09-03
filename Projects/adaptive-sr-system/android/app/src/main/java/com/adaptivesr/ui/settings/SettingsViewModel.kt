package com.adaptivesr.ui.settings

import androidx.lifecycle.ViewModel
import com.adaptivesr.data.TokenStore
import com.adaptivesr.data.remote.RaindropApi
import com.adaptivesr.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val client: SupabaseClient,
  private val api: RaindropApi,
  private val tokens: TokenStore,
  @ApplicationScope io: CoroutineContext
) : ViewModel() {
  // Own scope (same Task 3 idiom) so unit tests can inject a TestDispatcher.
  private val scope = CoroutineScope(SupervisorJob() + io)

  private val _supabaseOk = MutableStateFlow<Boolean?>(null)
  val supabaseOk: StateFlow<Boolean?> = _supabaseOk

  private val _raindropOk = MutableStateFlow<Boolean?>(null)
  val raindropOk: StateFlow<Boolean?> = _raindropOk

  fun saveSupabaseJwt(v: String) {
    scope.launch { tokens.setSupabaseJwt(v) }
  }

  fun saveRaindropToken(v: String) {
    scope.launch { tokens.setRaindropToken(v) }
  }

  fun saveFcmToken(v: String) {
    scope.launch { tokens.setFcmToken(v) }
  }

  // SELECT v_stats LIMIT 1: 2xx (no throw) means the pasted JWT works.
  suspend fun testSupabase(): Boolean {
    val ok = runCatching {
      val jwt = tokens.supabaseJwt.first()
      client.from("v_stats").select {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
        limit(count = 1L)
      }
      true
    }.getOrDefault(false)
    _supabaseOk.value = ok
    return ok
  }

  // GET /rest/v1/user: 2xx (no HttpException) means the pasted token works.
  suspend fun testRaindrop(): Boolean {
    val ok = runCatching {
      val token = tokens.raindropToken.first() ?: return@runCatching false
      api.user("Bearer $token").close()
      true
    }.getOrDefault(false)
    _raindropOk.value = ok
    return ok
  }

  override fun onCleared() {
    scope.cancel()
    super.onCleared()
  }
}
