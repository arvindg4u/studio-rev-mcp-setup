package com.adaptivesr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        AdaptiveSrNav()
      }
    }
  }
}

private val TABS = listOf("today", "library", "stats")
private val TAB_LABELS = mapOf("today" to "Today", "library" to "Library", "stats" to "Stats")

@Composable
fun AdaptiveSrNav() {
  val nav = rememberNavController()
  val backStack by nav.currentBackStackEntryAsState()
  val route = backStack?.destination?.route
  Scaffold(
    bottomBar = {
      NavigationBar {
        TABS.forEach { tab ->
          NavigationBarItem(
            selected = route == tab,
            onClick = { nav.navigate(tab) { launchSingleTop = true } },
            icon = {},
            label = { Text(TAB_LABELS.getValue(tab)) }
          )
        }
        NavigationBarItem(
          selected = route == "settings",
          onClick = { nav.navigate("settings") { launchSingleTop = true } },
          icon = {},
          label = { Text("Settings") }
        )
      }
    },
    floatingActionButton = {
      if (route == "today") {
        FloatingActionButton(onClick = { nav.navigate("add") }) {
          Text("+")
        }
      }
    }
  ) { inner ->
    Box(Modifier.padding(inner)) {
      NavHost(navController = nav, startDestination = "today") {
        composable("today") { PlaceholderScreen("Today — due queue lands in Task 3") }
        composable("library") { PlaceholderScreen("Library — lands in Task 5") }
        composable("stats") { PlaceholderScreen("Stats — lands in Task 6") }
        composable("add") { PlaceholderScreen("Add — lands in Task 4") }
        composable("settings") { PlaceholderScreen("Settings — paste tokens here (Task 6)") }
      }
    }
  }
}

@Composable
fun PlaceholderScreen(text: String) {
  Box {
    Text(text)
  }
}
