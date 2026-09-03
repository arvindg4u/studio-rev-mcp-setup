package com.adaptivesr.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(vm: LibraryViewModel = hiltViewModel()) {
  val all by vm.allTab.collectAsStateWithLifecycle()
  val drops by vm.raindropTab.collectAsStateWithLifecycle()
  var tab by remember { mutableIntStateOf(0) }
  var q by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  Column(Modifier.fillMaxSize().padding(8.dp)) {
    TextField(
      value = q,
      onValueChange = { q = it; vm.search(it) },
      label = { Text("Search") },
      modifier = Modifier.fillMaxWidth()
    )
    TabRow(selectedTabIndex = tab) {
      Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("All SR") })
      Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Raindrop") })
    }
    if (tab == 0) {
      LazyColumn(Modifier.fillMaxSize()) {
        items(all, key = { it.id }) { c ->
          Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(8.dp)) {
              Text(c.title, style = MaterialTheme.typography.titleSmall)
              c.link?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
          }
        }
      }
    } else {
      LazyColumn(Modifier.fillMaxSize()) {
        items(drops, key = { it.id }) { item ->
          Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                item.link?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
              }
              Text("SR", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
              Switch(
                checked = item.srEnabled,
                onCheckedChange = { scope.launch { vm.setSrTag(item, it) } }
              )
            }
          }
        }
      }
    }
  }
}
