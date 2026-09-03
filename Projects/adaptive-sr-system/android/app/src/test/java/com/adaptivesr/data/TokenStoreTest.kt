package com.adaptivesr.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenStoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private fun store() = TokenStore(
    PreferenceDataStoreFactory.create { tmp.newFile("t.preferences_pb") }
  )
  @Test fun roundTripsTokens() = runTest {
    val s = store()
    s.setSupabaseJwt("jwt-1"); s.setRaindropToken("rd-1"); s.setFcmToken("fcm-1")
    assertEquals("jwt-1", s.supabaseJwt.first())
    assertEquals("rd-1", s.raindropToken.first())
    assertEquals("fcm-1", s.fcmToken.first())
  }
}
