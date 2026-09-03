package com.adaptivesr.ui.stats

// Created early in Task 2 so SrRepository.stats() compiles; full consumers land in Task 6.
// Fields are final — Task 6 consumes as-is, never redefines.
data class StatsUi(
  val active: Int = 0,
  val due: Int = 0,
  val mastered: Int = 0,
  val masteryRate: Double = 0.0,
  val ratings: Map<String, Int> = emptyMap(),
  val hardTopics: List<String> = emptyList(),
  val lastPull: Long? = null,
  val lastFlush: Long? = null,
  val pendingCount: Int = 0,
  val degraded: Boolean = false
)
