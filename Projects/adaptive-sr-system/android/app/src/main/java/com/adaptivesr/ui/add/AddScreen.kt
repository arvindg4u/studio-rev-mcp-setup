package com.adaptivesr.ui.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun AddScreen(prefill: String? = null, onSaved: () -> Unit, vm: AddViewModel = hiltViewModel()) {
  val ui by vm.uiState.collectAsStateWithLifecycle()
  var title by remember { mutableStateOf(prefill ?: "") }
  var link by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  LaunchedEffect(prefill) {
    if (prefill != null) vm.prefill(prefill)
  }
  LaunchedEffect(ui.saved) {
    if (ui.saved) onSaved()
  }

  Column(Modifier.fillMaxSize().padding(16.dp)) {
    TextField(
      value = title,
      onValueChange = { title = it },
      label = { Text("Title") },
      modifier = Modifier.fillMaxWidth()
    )
    TextField(
      value = link,
      onValueChange = { link = it },
      label = { Text("Link (optional)") },
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    Button(
      onClick = { scope.launch { vm.save(title, link.ifBlank { null }) } },
      modifier = Modifier.padding(top = 12.dp)
    ) {
      Text("Save offline")
    }
  }
}
