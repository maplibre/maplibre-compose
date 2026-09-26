package org.maplibre.compose.benchmark

import kotlinx.serialization.encodeToString

fun WorkloadReport.printResult() {
  submissionMs.chunked(32).forEach { batch ->
    println("MAP_BENCHMARK SUBMISSIONS " + BenchmarkJsonWithDefaults.encodeToString(batch))
  }
  completionMs.chunked(32).forEach { batch ->
    println("MAP_BENCHMARK COMPLETIONS " + BenchmarkJsonWithDefaults.encodeToString(batch))
  }
  println(
    "MAP_BENCHMARK WORKLOAD " +
      BenchmarkJsonWithDefaults.encodeToString(
        copy(
          submissionMs = emptyList(),
          completionMs = emptyList(),
          submissionCount = submissionMs.size,
          completionCount = completionMs.size,
        )
      )
  )
}
