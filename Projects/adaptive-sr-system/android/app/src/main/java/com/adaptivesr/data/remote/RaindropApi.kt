package com.adaptivesr.data.remote

import com.adaptivesr.data.TokenStore
import com.adaptivesr.ui.library.RaindropItem
import com.adaptivesr.ui.library.RaindropSource
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Nullable DTO fields + elvis mapping (no moshi-kotlin adapter needed): a
// missing array degrades to empty rather than throwing inside Retrofit.
data class RdList(val items: List<RdItem>? = null)
data class RdSingle(val item: RdItem? = null)
data class RdItem(val _id: Long, val title: String, val link: String?, val tags: List<String>? = null)

interface RaindropApi {
  @GET("rest/v1/raindrops/{c}")
  suspend fun searchRaindrops(
    @Header("Authorization") auth: String,
    @Path("c") collection: Int,
    @Query("search") search: String,
    @Query("perpage") per: Int = 50
  ): RdList

  @GET("rest/v1/collections")
  suspend fun collections(@Header("Authorization") auth: String): Any

  // Deviation from plan (which lists 3 methods): GET-single mirrors the GAS
  // removeRaindropSRTag flow (GET then PUT) so the toggle edits live tags
  // instead of guessing them.
  @GET("rest/v1/raindrop/{id}")
  suspend fun getRaindrop(@Header("Authorization") auth: String, @Path("id") id: Long): RdSingle

  @PUT("rest/v1/raindrop/{id}")
  suspend fun updateTags(
    @Header("Authorization") auth: String,
    @Path("id") id: Long,
    @Body body: Map<String, List<String>>
  ): Any
}

/** Canonical SR tag — mirrors GAS `SR_TAG` default (`config.SR_TAG || 'SR'`). */
const val SR_TAG = "SR"

/**
 * Production [RaindropSource]. Collection 0 = all raindrops. Auth is passed
 * per-call from [TokenStore] (same paste-your-token pattern as the Supabase
 * bearer override). Failures degrade to an empty list; the toggle path
 * surfaces errors through the repo outbox instead.
 */
class RetrofitRaindropSource @Inject constructor(
  private val api: RaindropApi,
  private val tokens: TokenStore
) : RaindropSource {
  override suspend fun list(search: String): List<RaindropItem> = runCatching {
    val token = tokens.raindropToken.first() ?: return emptyList()
    val auth = "Bearer $token"
    (api.searchRaindrops(auth, 0, search).items ?: emptyList()).map {
      RaindropItem(it._id, it.title, it.link, it.tags.orEmpty().any { t -> t.equals(SR_TAG, ignoreCase = true) })
    }
  }.getOrDefault(emptyList())
}
