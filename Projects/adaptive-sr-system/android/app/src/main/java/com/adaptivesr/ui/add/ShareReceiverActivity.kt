package com.adaptivesr.ui.add

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.adaptivesr.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Share target + deep-link entry. Extracts shared text (SEND intent) or the
 * `?text=` deep-link param, forwards it to [MainActivity] on the `add` route,
 * and finishes — no UI of its own.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val text: String? = when (intent?.action) {
      Intent.ACTION_SEND -> intent.getCharSequenceExtra(EXTRA_TEXT)?.toString()
      Intent.ACTION_VIEW -> intent.data?.getQueryParameter("text")
      else -> null
    }
    val forward = Intent(this, MainActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse("adaptivesr://add").buildUpon()
        .apply { if (!text.isNullOrBlank()) appendQueryParameter("text", text) }
        .build()
    }
    startActivity(forward)
    finish()
  }

  companion object {
    const val EXTRA_TEXT = Intent.EXTRA_TEXT
  }
}
