package org.maplibre.compose.benchmark.classic

import android.os.Bundle
import android.os.Process
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import kotlin.coroutines.resume
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.compose.benchmark.*

class MainActivity : ComponentActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var driver: ClassicAndroidDriver? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    enableEdgeToEdge()
    MapLibre.getInstance(this)
    val container = FrameLayout(this)
    setContentView(container)
    scope.launch {
      try {
        val config =
          checkNotNull(BenchmarkConfig.parse(intent.getStringExtra("benchmark"))) {
            "Missing benchmark configuration"
          }
        require(config.implementation == BenchmarkImplementation.ClassicAndroid)
        val fixture =
          loadBenchmarkFixture(
            config,
            read = {
              assets.open("benchmarks/$it").bufferedReader().use { reader -> reader.readText() }
            },
            uri = { "asset://benchmarks/$it" },
          )
        fun createDriver(): ClassicAndroidDriver {
          val created = TimeSource.Monotonic.markNow()
          val view =
            MapView(
              this@MainActivity,
              MapLibreMapOptions.createFromAttributes(this@MainActivity)
                .textureMode(config.surface == "texture"),
            )
          view.onCreate(null)
          container.addView(view, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
          return ClassicAndroidDriver(fixture, view, created).also {
            driver = it
            // The coroutine reaches this before onStart/onResume, or while already resumed.
            if (started) view.onStart()
            if (resumed) view.onResume()
          }
        }
        var startCpu = 0L
        val host =
          BenchmarkHost(
            cpu = { active ->
              if (active) startCpu = Process.getElapsedCpuTime()
              else println("MAP_BENCHMARK CPU ${Process.getElapsedCpuTime() - startCpu}")
            },
            collectGarbage = System::gc,
            uiFrames =
              AndroidUiFrames(window, intent.getBooleanExtra("capturePresentation", false)),
          )
        if (config.scenario == BenchmarkScenario.MapReturn) {
          val cover = View(this@MainActivity).apply { setBackgroundColor(0xff303030.toInt()) }
          container.addView(cover, FrameLayout.LayoutParams(-1, -1))
          runMapReturnBenchmark(
            config,
            ::nextAndroidFrame,
            mount = ::createDriver,
            unmount = { active ->
              container.removeView((active as ClassicAndroidDriver).view)
              driver = null
              repeat(2) { nextAndroidFrame() }
            },
            cover = { cover.translationX = container.width * it.toFloat() },
            host = host,
          )
        } else runBenchmark(createDriver(), host)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        println("MAP_BENCHMARK ERROR ${e.message}")
      }
    }
  }

  private var started = false
  private var resumed = false

  override fun onStart() {
    super.onStart()
    started = true
    driver?.view?.takeUnless { it.isDestroyed }?.onStart()
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    driver?.view?.takeUnless { it.isDestroyed }?.onResume()
  }

  override fun onPause() {
    resumed = false
    driver?.view?.takeUnless { it.isDestroyed }?.onPause()
    super.onPause()
  }

  override fun onStop() {
    started = false
    scope.cancel()
    driver?.view?.takeUnless { it.isDestroyed }?.onStop()
    super.onStop()
  }

  override fun onDestroy() {
    scope.cancel()
    driver?.close()
    super.onDestroy()
  }
}

internal suspend fun nextAndroidFrame(): Long = suspendCancellableCoroutine { continuation ->
  val choreographer = Choreographer.getInstance()
  val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(it) }
  choreographer.postFrameCallback(callback)
  continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}
