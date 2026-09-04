package com.adaptivesr.di

import com.google.crypto.tink.Aead
import org.junit.Assert.*
import org.junit.Test

private class FakeAead : Aead {
  override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = plaintext
  override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray = ciphertext
}

class AeadOrNullTest {
  @Test fun returnsValueWhenBuildSucceeds() {
    val fake = FakeAead()
    assertSame(fake, aeadOrNull { fake })
  }

  @Test fun returnsNullInsteadOfThrowing() {
    // Mirrors the 2026-09-03 logcat crash: AndroidKeysetManager.build() threw
    // GeneralSecurityException ("No key manager found for ...AesGcmKey"),
    // which must degrade to plaintext TokenStore, never crash the process.
    val r = aeadOrNull { throw java.security.GeneralSecurityException("No key manager found") }
    assertNull(r)
  }
}
