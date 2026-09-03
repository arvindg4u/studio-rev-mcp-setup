package com.adaptivesr.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adaptivesr.core.Rating
import com.adaptivesr.work.WorkerScheduler

@Composable
fun TodayScreen(vm: TodayViewModel = hiltViewModel()) {
  val state by vm.uiState.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  var masterTarget: String? by remember { mutableStateOf(null) }
  Column(Modifier.fillMaxSize().padding(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Due: ${state.items.size}  Queued: ${state.pendingCount}", style = MaterialTheme.typography.titleMedium)
      Button(onClick = { vm.refresh(); WorkerScheduler.pullNow(ctx) }) { Text("Refresh") }
    }
    LazyColumn(Modifier.fillMaxSize()) {
      items(state.items, key = { it.card.id }) { row ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(row.card.title, style = MaterialTheme.typography.titleSmall)
              if (row.queued) Text("queued", style = MaterialTheme.typography.labelSmall)
            }
            Text(
              if (row.overdueDays > 0) "${row.overdueDays}d overdue" else "due today",
              style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              for (r in listOf(Rating.EASY, Rating.GOOD, Rating.HARD, Rating.RELEARN)) {
                Button(onClick = { vm.rate(row.card.id, r) }) {
                  Text("$r ${row.previews[r]}")
                }
              }
              Button(onClick = { masterTarget = row.card.id }) {
                Text("MASTER ${row.previews[Rating.MASTER]}")
              }
            }
          }
        }
      }
    }
  }
  masterTarget?.let { id ->
    AlertDialog(
      onDismissRequest = { masterTarget = null },
      confirmButton = { TextButton(onClick = { vm.confirmMaster(id); masterTarget = null }) { Text("Master it") } },
      dismissButton = { TextButton(onClick = { masterTarget = null }) { Text("Cancel") } },
      title = { Text("Master this card?") },
      text = { Text("It will be suspended and hidden from the queue.") }
    )
  }
}
