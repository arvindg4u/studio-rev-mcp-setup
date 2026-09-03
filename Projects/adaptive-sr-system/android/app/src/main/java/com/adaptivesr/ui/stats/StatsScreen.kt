package com.adaptivesr.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun StatsScreen(vm: StatsViewModel = hiltViewModel()) {
  val s by vm.uiState.collectAsStateWithLifecycle()
  LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Active", s.active.toString(), Modifier.weight(1f))
        StatTile("Due", s.due.toString(), Modifier.weight(1f))
        StatTile("Mastered", s.mastered.toString(), Modifier.weight(1f))
      }
    }
    item {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
          Text("Mastery rate: ${(s.masteryRate * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
          RatingsDonut(s.ratings)
        }
      }
    }
    item {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
          Text("Hard topics", style = MaterialTheme.typography.titleSmall)
          if (s.hardTopics.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodySmall)
          s.hardTopics.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
      }
    }
    item {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
          val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
          Text("Last pull: ${s.lastPull?.let { fmt.format(Date(it)) } ?: "never"}", style = MaterialTheme.typography.bodySmall)
          Text("Last flush: ${s.lastFlush?.let { fmt.format(Date(it)) } ?: "never"}", style = MaterialTheme.typography.bodySmall)
          Text("Pending: ${s.pendingCount}", style = MaterialTheme.typography.bodySmall)
          if (s.degraded) Text("DEGRADED", color = Color.Red, style = MaterialTheme.typography.titleSmall)
          else Text("In sync", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
  Card(modifier) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(value, style = MaterialTheme.typography.headlineSmall)
      Text(label, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun RatingsDonut(ratings: Map<String, Int>) {
  val total = ratings.values.sum().coerceAtLeast(1)
  val colors = mapOf(
    "MASTER" to Color(0xFF4CAF50), "EASY" to Color(0xFF8BC34A),
    "GOOD" to Color(0xFF2196F3), "HARD" to Color(0xFFFF9800),
    "RELEARN" to Color(0xFFF44336)
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(96.dp).padding(8.dp)) {
      var start = -90f
      ratings.forEach { (k, v) ->
        val sweep = 360f * v / total
        drawArc(colors[k] ?: Color.Gray, start, sweep, useCenter = true)
        start += sweep
      }
    }
    Column {
      if (ratings.isEmpty()) Text("No ratings yet", style = MaterialTheme.typography.bodySmall)
      ratings.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
    }
  }
}
