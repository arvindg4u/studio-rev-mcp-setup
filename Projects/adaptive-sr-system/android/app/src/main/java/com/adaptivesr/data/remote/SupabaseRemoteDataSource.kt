package com.adaptivesr.data.remote

import com.adaptivesr.core.ApiResult
import com.adaptivesr.core.ErrorCode
import com.adaptivesr.core.Rating
import com.adaptivesr.data.TokenStore
import com.adaptivesr.data.local.CardEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface SupabaseRemoteDataSource {
  suspend fun pullDue(): ApiResult<List<CardEntity>>
  suspend fun flushReview(cardId: String, rating: Rating, key: String): ApiResult<Unit>
  suspend fun insertCard(card: CardEntity): ApiResult<Unit>
  suspend fun fetchStats(): ApiResult<String>
  suspend fun searchRemote(q: String): ApiResult<List<CardEntity>>
  suspend fun setSrTag(raindropId: Long, enabled: Boolean): ApiResult<Unit>
}

/**
 * Postgrest-backed remote. Auth comes per-call from [TokenStore] (paste-your-JWT
 * can change at runtime while the singleton client cannot), so every call sets
 * the bearer header override explicitly.
 *
 * No `@Serializable` DTOs here on purpose: rows are parsed from [JsonObject]
 * structurally, so the kotlinx-serialization compiler plugin is not required.
 */
class SupabaseRemoteDataSourceImpl @Inject constructor(
  private val client: SupabaseClient,
  private val tokens: TokenStore,
  private val raindrop: RaindropApi
) : SupabaseRemoteDataSource {

  override suspend fun pullDue(): ApiResult<List<CardEntity>> {
    return try {
      val jwt = tokens.supabaseJwt.first()
      val nowIso = Instant.now().toString()
      val raw = client.from("cards").select {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
        filter {
          eq("suspended", false)
          lte("next_review_at", nowIso)
        }
        order("next_review_at", Order.ASCENDING)
        order("id", Order.ASCENDING)
      }.data
      ApiResult.Ok(Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject.toCardEntity() })
    } catch (e: RestException) {
      ApiResult.Err(restToCode(e))
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }

  override suspend fun flushReview(cardId: String, rating: Rating, key: String): ApiResult<Unit> {
    return try {
      val jwt = tokens.supabaseJwt.first()
      val params = buildJsonObject {
        put("p_card_id", cardId)
        put("p_rating", rating.name)
        put("p_idempotency_key", key)
      }
      val raw = client.postgrest.rpc("process_review", params) {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
      }.data
      val env = Json.parseToJsonElement(raw).jsonObject
      val success = env["success"]?.jsonPrimitive?.booleanOrNull ?: false
      if (!success) return ApiResult.Err(parseErrorCode(env) ?: ErrorCode.UNKNOWN)
      val already = env["data"]?.jsonObject?.get("alreadyProcessed")?.jsonPrimitive?.booleanOrNull ?: false
      if (already) ApiResult.Err(ErrorCode.ALREADY_PROCESSED) else ApiResult.Ok(Unit)
    } catch (e: RestException) {
      ApiResult.Err(restToCode(e))
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }

  override suspend fun insertCard(card: CardEntity): ApiResult<Unit> {
    return try {
      val jwt = tokens.supabaseJwt.first()
      val params = buildJsonObject {
        put("title", card.title)
        put("link", card.link)
        put("source", card.source)
        card.raindropId?.let { put("raindrop_id", it) }
        card.collection?.let { put("collection", it) }
      }
      client.from("cards").insert(params) {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
      }
      ApiResult.Ok(Unit)
    } catch (e: RestException) {
      ApiResult.Err(restToCode(e))
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }

  override suspend fun fetchStats(): ApiResult<String> {
    return try {
      val jwt = tokens.supabaseJwt.first()
      val raw = client.postgrest.rpc("get_dashboard_stats") {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
      }.data
      ApiResult.Ok(raw)
    } catch (e: RestException) {
      ApiResult.Err(restToCode(e))
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }

  override suspend fun searchRemote(q: String): ApiResult<List<CardEntity>> {
    return try {
      val jwt = tokens.supabaseJwt.first()
      val params = buildJsonObject {
        put("p_filter", "all")
        put("p_query", q)
        put("p_limit", 50)
      }
      val raw = client.postgrest.rpc("list_items", params) {
        if (!jwt.isNullOrBlank()) headers["Authorization"] = "Bearer $jwt"
      }.data
      val env = Json.parseToJsonElement(raw).jsonObject
      val success = env["success"]?.jsonPrimitive?.booleanOrNull ?: false
      if (!success) return ApiResult.Err(parseErrorCode(env) ?: ErrorCode.UNKNOWN)
      val items: JsonArray = env["data"]?.jsonObject?.get("items")?.jsonArray ?: JsonArray(emptyList())
      ApiResult.Ok(items.map { it.jsonObject.toCardEntity() })
    } catch (e: RestException) {
      ApiResult.Err(restToCode(e))
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }

  // GET-then-PUT mirrors the GAS removeRaindropSRTag flow: read the live
  // bookmark, edit only its tags, PUT back. Tags-only body leaves the note
  // untouched; comparison is case-insensitive per the GAS SR_TAG rule.
  override suspend fun setSrTag(raindropId: Long, enabled: Boolean): ApiResult<Unit> {
    return try {
      val token = tokens.raindropToken.first() ?: return ApiResult.Err(ErrorCode.UNKNOWN)
      val auth = "Bearer $token"
      val current = raindrop.getRaindrop(auth, raindropId).item
        ?: return ApiResult.Err(ErrorCode.NOT_FOUND)
      val tags = current.tags.orEmpty().toMutableList()
      val has = tags.any { it.equals(SR_TAG, ignoreCase = true) }
      if (enabled && !has) tags.add(SR_TAG)
      if (!enabled && has) tags.removeAll { it.equals(SR_TAG, ignoreCase = true) }
      raindrop.updateTags(auth, raindropId, mapOf("tags" to tags))
      ApiResult.Ok(Unit)
    } catch (e: retrofit2.HttpException) {
      ApiResult.Err(when (e.code()) {
        404 -> ErrorCode.NOT_FOUND
        429 -> ErrorCode.RATE_LIMITED
        else -> ErrorCode.UNKNOWN
      })
    } catch (e: IOException) {
      ApiResult.Err(ErrorCode.NETWORK)
    } catch (e: Exception) {
      ApiResult.Err(ErrorCode.UNKNOWN)
    }
  }
}

private fun restToCode(e: RestException): ErrorCode = when (e.statusCode) {
  400 -> ErrorCode.INVALID_RATING
  404 -> ErrorCode.NOT_FOUND
  409 -> ErrorCode.ALREADY_PROCESSED
  429 -> ErrorCode.RATE_LIMITED
  else -> ErrorCode.UNKNOWN
}

private fun parseErrorCode(env: JsonObject): ErrorCode? {
  val code = env.objStr("errorCode") ?: return null
  return runCatching { ErrorCode.valueOf(code) }.getOrElse { ErrorCode.UNKNOWN }
}

private fun JsonObject.objStr(key: String): String? {
  val p = this[key] as? JsonPrimitive ?: return null
  if (p is JsonNull) return null
  return p.contentOrNull
}

private fun JsonObject.objLong(key: String): Long? = objStr(key)?.toLongOrNull()

private fun JsonObject.objInt(key: String, default: Int = 0): Int =
  objStr(key)?.toIntOrNull() ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

private fun JsonObject.objBool(key: String, default: Boolean = false): Boolean =
  (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.objInstantMillis(key: String): Long? =
  objStr(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun JsonObject.toCardEntity(): CardEntity = CardEntity(
  id = objStr("id") ?: UUID.randomUUID().toString(),
  title = objStr("title") ?: "",
  link = objStr("link"),
  source = objStr("source") ?: "APP",
  raindropId = objLong("raindrop_id"),
  collection = objStr("collection"),
  reviewCount = (this["review_count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    ?: objInt("review_count"),
  intervalDays = (this["interval_days"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    ?: objInt("interval_days"),
  lastRating = objStr("last_rating"),
  status = objStr("status") ?: "NEW",
  suspended = objBool("suspended"),
  // list_items names the due timestamp "dueDate"; direct selects use "next_review_at".
  dueAt = objInstantMillis("next_review_at") ?: objInstantMillis("dueDate"),
  lastReviewedAt = objInstantMillis("last_reviewed_at"),
  updatedAt = objInstantMillis("updated_at") ?: System.currentTimeMillis()
)
