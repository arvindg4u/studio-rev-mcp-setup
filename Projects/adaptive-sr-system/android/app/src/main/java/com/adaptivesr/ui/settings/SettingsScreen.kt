package com.adaptivesr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
  val supabaseOk by vm.supabaseOk.collectAsStateWithLifecycle()
  val raindropOk by vm.raindropOk.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var supabaseJwt by remember { mutableStateOf("") }
  var raindropToken by remember { mutableStateOf("") }
  var fcmToken by remember { mutableStateOf("") }

  Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TokenRow("Supabase JWT", supabaseJwt, { supabaseJwt = it }, {
      vm.saveSupabaseJwt(supabaseJwt); supabaseJwt = ""
    })
    TokenRow("Raindrop token", raindropToken, { raindropToken = it }, {
      vm.saveRaindropToken(raindropToken); raindropToken = ""
    })
    TokenRow("FCM token", fcmToken, { fcmToken = it }, {
      vm.saveFcmToken(fcmToken); fcmToken = ""
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { scope.launch { vm.testSupabase() } }) { Text("Test Supabase") }
      Text(resultText(supabaseOk), style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { scope.launch { vm.testRaindrop() } }) { Text("Test Raindrop") }
      Text(resultText(raindropOk), style = MaterialTheme.typography.bodySmall)
    }
  }
}

private fun resultText(ok: Boolean?): String = when (ok) {
  null -> "not tested"
  true -> "PASS"
  false -> "FAIL"
}

@Composable
private fun TokenRow(
  label: String,
  value: String,
  onValue: (String) -> Unit,
  onSave: () -> Unit
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextField(
      value = value,
      onValueChange = onValue,
      label = { Text(label) },
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.weight(1f)
    )
    Button(onClick = onSave) { Text("Save") }
  }
}
