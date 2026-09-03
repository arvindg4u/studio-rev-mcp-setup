package com.adaptivesr.data

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val SUPABASE_JWT = stringPreferencesKey("supabase_jwt")
val RAINDROP_TOKEN = stringPreferencesKey("raindrop_token")
val FCM_TOKEN = stringPreferencesKey("fcm_token")

/**
 * Token storage. Values are AES256-GCM encrypted with Tink when an [aead]
 * is provided (production via [com.adaptivesr.di.AppModule]); a null [aead]
 * stores plaintext (unit tests only).
 */
class TokenStore(
  private val ds: DataStore<Preferences>,
  private val aead: Aead? = null
) {
  val supabaseJwt: Flow<String?> = ds.data.map { decrypt(it[SUPABASE_JWT]) }
  val raindropToken: Flow<String?> = ds.data.map { decrypt(it[RAINDROP_TOKEN]) }
  val fcmToken: Flow<String?> = ds.data.map { decrypt(it[FCM_TOKEN]) }

  suspend fun setSupabaseJwt(v: String) {
    ds.edit { it[SUPABASE_JWT] = encrypt(v) }
  }

  suspend fun setRaindropToken(v: String) {
    ds.edit { it[RAINDROP_TOKEN] = encrypt(v) }
  }

  suspend fun setFcmToken(v: String) {
    ds.edit { it[FCM_TOKEN] = encrypt(v) }
  }

  private fun encrypt(v: String): String {
    val a = aead ?: return v
    return Base64.encodeToString(a.encrypt(v.toByteArray(Charsets.UTF_8), null), Base64.NO_WRAP)
  }

  private fun decrypt(stored: String?): String? {
    if (stored == null) return null
    val a = aead ?: return stored
    return try {
      String(a.decrypt(Base64.decode(stored, Base64.NO_WRAP), null), Charsets.UTF_8)
    } catch (e: Exception) {
      null
    }
  }
}
