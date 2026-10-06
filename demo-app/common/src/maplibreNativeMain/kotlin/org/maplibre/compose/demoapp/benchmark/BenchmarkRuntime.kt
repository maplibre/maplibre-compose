package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.offline.OfflineStorageState

@Composable internal expect fun benchmarkCacheDirectory(): String

@Composable
internal actual fun BenchmarkRuntime(config: BenchmarkConfig, onStatus: (String, Boolean) -> Unit) {
  val directory = benchmarkCacheDirectory()
  val density = LocalDensity.current.density
  val window = LocalWindowInfo.current
  val uiFrames = rememberBenchmarkUiFrames()
  LaunchedEffect(config, directory) {
    onStatus("Starting", true)
    try {
      val file = Path(directory, "maplibre-benchmark-runtime.db")
      // Only this workload uses these files; it never creates packs or downloads resources.
      for (suffix in listOf("", "-wal", "-shm")) {
        SystemFileSystem.delete(Path(file.toString() + suffix), mustExist = false)
      }
      val options = MapRuntimeOptions(cacheFile = file, maximumCacheSizeBytes = 16L * 1024 * 1024)
      withFrameNanos {}
      val size = window.containerSize
      val host =
        BenchmarkHost(::benchmarkCpu, ::benchmarkCollectGarbage, uiFrames) { onStatus(it, true) }
      val failure =
        runRuntimeBenchmark(
          config,
          nextFrame = { withFrameNanos { it } },
          viewport =
            listOf(
              (size.width / density).toDouble(),
              (size.height / density).toDouble(),
              density.toDouble(),
            ),
          create = { createMapRuntime(options) },
          awaitReady = { runtime ->
            when (
              val state = runtime.offlineStorage.state.first { it !is OfflineStorageState.Loading }
            ) {
              is OfflineStorageState.Ready ->
                check(state.packs.isEmpty()) { "Benchmark cache must contain no offline packs" }
              is OfflineStorageState.Failed -> throw state.cause
              else -> error("Offline storage did not become ready: $state")
            }
          },
          close = { it.close() },
          awaitClosed = { it.awaitClosed() },
          host = host,
        )
      onStatus(failure ?: "Done. Results are in the benchmark log.", false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Runtime benchmark failed", false)
    }
  }
}

internal actual val supportsRuntimeBenchmark: Boolean = true
