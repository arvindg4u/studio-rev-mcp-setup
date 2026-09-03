package com.adaptivesr.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adaptivesr.core.ApiResult
import com.adaptivesr.data.local.CardDao
import com.adaptivesr.data.local.SyncMeta
import com.adaptivesr.data.local.SyncMetaDao
import com.adaptivesr.data.remote.SupabaseRemoteDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Nightly drift check: compares the Room due count against the server
 * `get_dashboard_stats` due count. On drift sets SyncMeta("degraded","1")
 * (surfaced red in StatsScreen); on match clears it to "0". Parse or network
 * failures are fail-open — the flag is left untouched, never set on error.
 */
@HiltWorker
class BackupCheckWorker @AssistedInject constructor(
  @Assisted ctx: Context,
  @Assisted params: WorkerParameters,
  private val dao: CardDao,
  private val meta: SyncMetaDao,
  private val remote: SupabaseRemoteDataSource
) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    return try {
      val localDue = dao.dueQueue(System.currentTimeMillis()).first().size
      when (val r = remote.fetchStats()) {
        is ApiResult.Ok -> {
          parseDue(r.data)?.let { serverDue ->
            meta.put(SyncMeta("degraded", if (serverDue != localDue) "1" else "0"))
          }
          Result.success()
        }
        is ApiResult.Err -> Result.retry()
      }
    } catch (e: Exception) {
      Result.retry()
    }
  }

  internal fun parseDue(raw: String): Int? = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    val data = (root["data"] as? JsonObject) ?: root
    ((data["due"] as? JsonPrimitive)?.contentOrNull)?.toIntOrNull()
  }.getOrNull()
}
