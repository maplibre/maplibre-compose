package org.maplibre.compose.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * Temporary device benchmark of the Android FFI host. Not part of the regression suite; run it
 * alone against a physical device.
 */
@Ignore
@OptIn(ExperimentalTestApi::class)
class AndroidMapBenchmark {

  @Test
  fun runDisposableBenchmark() {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    try {
      runAndroidComposeUiTest<ComponentActivity>(testTimeout = 15.minutes) {
        val activity = requireNotNull(activity)
        activity.runOnMainSync {
          activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val context = activity.applicationContext
        val packageName = context.packageName
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sampler = MemorySampler(activityManager)
        val geoJson =
          largePointCollectionJson(
            count = GEOJSON_FEATURES,
            originLon = CITY_TARGET.longitude - 0.012,
            originLat = CITY_TARGET.latitude - 0.01,
            step = 0.00012,
          )
        MlnFfiApplication.configure(
          MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
        )
        val host = BenchHost()
        val refreshHz = activity.displayRefreshHz()
        val frameSleepMs = (1000f / refreshHz).toLong().coerceIn(4L, 16L)
        val fpsOnly = benchSubset() == "fps"
        val modes = benchRenderModes()
        val settleMs = if (fpsOnly) FPS_QUICK_SETTLE_MS else TILE_SETTLE_MS
        val warmupMs = if (fpsOnly) FPS_QUICK_WARMUP_MS else FPS_WARMUP_MS
        val windowMs = if (fpsOnly) FPS_QUICK_WINDOW_MS else FPS_WINDOW_MS
        Log.i(BENCH_TAG, "Benchmark subset=${benchSubset()}")
        setContent {
          val spec = host.spec
          Column(Modifier.fillMaxSize()) {
            BenchStatus(host.status)
            Box(Modifier.weight(1f).fillMaxSize()) {
              if (spec == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
              } else {
                key(spec.state) {
                  BenchMap(
                    mode = spec.mode,
                    state = spec.state,
                    baseStyle = spec.baseStyle,
                    geoJson = spec.geoJson,
                    firstPosition = spec.firstPosition,
                  )
                }
              }
            }
          }
        }
        val scenarios = buildJsonArray {
          for (mode in modes) {
            add(
              runFpsScenario(
                activity,
                host,
                mode,
                sampler,
                packageName,
                frameSleepMs,
                settleMs = settleMs,
                warmupMs = warmupMs,
                windowMs = windowMs,
              )
            )
            if (!fpsOnly) {
              add(runLatencyScenario(activity, host, mode, reuseHostMap = true))
              add(runGeoJsonScenario(activity, host, mode, geoJson))
            }
            hideMap(activity, host)
            Runtime.getRuntime().gc()
            waitElapsed(if (fpsOnly) 300 else 1_000)
          }
        }
        val report = buildJsonObject {
          put("implementation", "mln-ffi")
          put("gitBranch", "agent/android-native-ffi")
          put("subset", benchSubset())
          putJsonObject("device") {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("release", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
          }
          put("refreshHz", refreshHz)
          put("frameSleepMs", frameSleepMs)
          put("style", LIBERTY.uri)
          put("geoJsonFeatures", GEOJSON_FEATURES)
          put("geoJsonSynchronousUpdate", true)
          put("scenarios", scenarios)
        }
        writeBenchmarkReport(File(context.cacheDir, "maplibre-benchmark-ffi.json"), report)
        activity.runOnMainSync { host.status = "Done" }
        waitElapsed(if (fpsOnly) 300 else 1_500)
      }
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private fun ComposeUiTest.runFpsScenario(
    activity: ComponentActivity,
    host: BenchHost,
    mode: RenderOptions.RenderMode,
    sampler: MemorySampler,
    packageName: String,
    frameSleepMs: Long,
    settleMs: Long = TILE_SETTLE_MS,
    warmupMs: Long = FPS_WARMUP_MS,
    windowMs: Long = FPS_WINDOW_MS,
  ) =
    namedScenario(activity, host, "FPS", mode) {
      val state = BenchMapState()
      showMap(activity, host, BenchSpec(mode = mode, state = state, baseStyle = LIBERTY))
      awaitLoaded(state)
      waitElapsed(settleMs)
      spinCamera(activity, state, warmupMs, frameSleepMs)
      state.frames.reset()
      gfxinfoReset(packageName)
      val cpuBefore = Process.getElapsedCpuTime()
      val wallBefore = SystemClock.elapsedRealtime()
      val samples = ArrayList<MemorySample>()
      samples += sampler.sample(0)
      spinCamera(activity, state, windowMs, frameSleepMs) { elapsed ->
        if (elapsed - (samples.lastOrNull()?.elapsedMs ?: 0) >= MEMORY_SAMPLE_MS) {
          samples += sampler.sample(elapsed)
        }
      }
      samples += sampler.sample(SystemClock.elapsedRealtime() - wallBefore)
      val cpuMs = Process.getElapsedCpuTime() - cpuBefore
      val stamps = state.frames.snapshotNs()
      val durationMs = SystemClock.elapsedRealtime() - wallBefore
      buildJsonObject {
        put("frames", stamps.size)
        put("durationMs", durationMs)
        put("fps", if (durationMs > 0) stamps.size * 1000.0 / durationMs else 0.0)
        putStats("frameMs", frameIntervalsMs(stamps))
        put("cpuMs", cpuMs)
        putMemory("memory", samples)
        put("gfxinfo", gfxinfoSummary(packageName))
      }
    }

  private fun ComposeUiTest.runLatencyScenario(
    activity: ComponentActivity,
    host: BenchHost,
    mode: RenderOptions.RenderMode,
    reuseHostMap: Boolean = false,
  ) =
    namedScenario(activity, host, "Latency", mode) {
      val state =
        if (reuseHostMap) {
          requireNotNull(host.spec).state
        } else {
          BenchMapState().also { next ->
            showMap(activity, host, BenchSpec(mode = mode, state = next, baseStyle = LIBERTY))
            awaitLoaded(next)
            waitElapsed(TILE_SETTLE_MS)
          }
        }
      waitUntilQuiet(state.frames)
      val samples = ArrayList<Double>(LATENCY_SAMPLES)
      for (index in 0 until LATENCY_SAMPLES) {
        waitUntilQuiet(state.frames)
        val before = state.frames.count()
        val startNs = SystemClock.elapsedRealtimeNanos()
        activity.runOnMainSync {
          val current = state.camera.position
          state.camera.position = current.copy(bearing = (current.bearing + 8.0) % 360.0)
        }
        await("camera frame ${index + 1}", FRAME_TIMEOUT_MS) { state.frames.count() > before }
        samples += (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
      }
      buildJsonObject { putStats("wakeupMs", samples) }
    }

  private fun ComposeUiTest.runGeoJsonScenario(
    activity: ComponentActivity,
    host: BenchHost,
    mode: RenderOptions.RenderMode,
    geoJson: String,
  ) =
    namedScenario(activity, host, "GeoJSON", mode) {
      val state = BenchMapState()
      showMap(
        activity,
        host,
        BenchSpec(mode = mode, state = state, baseStyle = LIBERTY, geoJson = true),
      )
      awaitLoaded(state)
      waitElapsed(TILE_SETTLE_MS)
      waitUntilQuiet(state.frames)
      val source = waitForSource(state)
      val before = state.frames.count()
      val startNs = SystemClock.elapsedRealtimeNanos()
      source.setData(GeoJsonData.JsonString(geoJson))
      val setDataMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
      await("geojson frame", GEOJSON_TIMEOUT_MS) { state.frames.count() > before }
      val firstFrameMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
      buildJsonObject {
        put("features", GEOJSON_FEATURES)
        put("jsonBytes", geoJson.length)
        put("setDataMs", setDataMs)
        put("timeToNextFrameMs", firstFrameMs)
      }
    }

  private fun ComposeUiTest.namedScenario(
    activity: ComponentActivity,
    host: BenchHost,
    name: String,
    mode: RenderOptions.RenderMode,
    body: ComposeUiTest.() -> JsonObject,
  ): JsonObject {
    val modeName = mode.reportName()
    activity.runOnMainSync { host.status = "$name · $modeName" }
    Log.i(BENCH_TAG, "Starting $name/$modeName")
    return try {
        buildJsonObject {
          put("name", name.lowercase())
          put("mode", modeName)
          put("ok", true)
          body().forEach { (key, value) -> put(key, value) }
        }
      } catch (error: Throwable) {
        Log.e(BENCH_TAG, "$name/$modeName failed", error)
        buildJsonObject {
          put("name", name.lowercase())
          put("mode", modeName)
          put("ok", false)
          put("error", error.message ?: error::class.simpleName ?: "error")
        }
      }
      .also { Log.i(BENCH_TAG, it.toString()) }
  }

  private fun ComposeUiTest.showMap(
    activity: ComponentActivity,
    host: BenchHost,
    spec: BenchSpec,
  ) {
    activity.runOnMainSync { host.spec = spec }
    waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { host.spec === spec }
  }

  private fun ComposeUiTest.hideMap(activity: ComponentActivity, host: BenchHost) {
    activity.runOnMainSync { host.spec = null }
    waitUntil(timeoutMillis = FRAME_TIMEOUT_MS) { host.spec == null }
    waitElapsed(500)
  }

  private fun ComposeUiTest.awaitLoaded(state: BenchMapState) {
    waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
      state.loaded || state.loadFailed != null || state.frames.count() > 0
    }
    check(state.loadFailed == null) { "Map failed to load: ${state.loadFailed}" }
    waitUntil(timeoutMillis = FRAME_TIMEOUT_MS) { state.frames.count() > 0 }
  }

  private fun ComposeUiTest.waitForSource(state: BenchMapState): GeoJsonSource {
    waitUntil(timeoutMillis = FRAME_TIMEOUT_MS) { state.source != null }
    return requireNotNull(state.source)
  }

  private fun waitUntilQuiet(frames: FrameClock) {
    var last = frames.count()
    var quietSince = SystemClock.elapsedRealtime()
    await("quiet window", FRAME_TIMEOUT_MS) {
      val current = frames.count()
      val now = SystemClock.elapsedRealtime()
      if (current != last) {
        last = current
        quietSince = now
      }
      now - quietSince >= QUIET_MS
    }
  }

  private fun spinCamera(
    activity: ComponentActivity,
    state: BenchMapState,
    durationMs: Long,
    frameSleepMs: Long,
    onTick: (elapsedMs: Long) -> Unit = {},
  ) {
    val start = SystemClock.elapsedRealtime()
    var bearing = CITY_CAMERA.bearing
    while (true) {
      val elapsed = SystemClock.elapsedRealtime() - start
      onTick(elapsed)
      if (elapsed >= durationMs) return
      bearing = (bearing + 6.0) % 360.0
      activity.runOnMainSync { state.camera.position = CITY_CAMERA.copy(bearing = bearing) }
      Thread.sleep(frameSleepMs)
    }
  }

  private fun waitElapsed(durationMs: Long, onTick: (elapsedMs: Long) -> Unit = {}) {
    val start = SystemClock.elapsedRealtime()
    while (true) {
      val elapsed = SystemClock.elapsedRealtime() - start
      onTick(elapsed)
      if (elapsed >= durationMs) return
      Thread.sleep(50)
    }
  }

  private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
    val start = SystemClock.elapsedRealtime()
    while (!condition()) {
      if (SystemClock.elapsedRealtime() - start > timeoutMs) {
        error("Timed out waiting for $description after ${timeoutMs}ms")
      }
      Thread.sleep(16)
    }
  }

  internal companion object {
    const val GEOJSON_FEATURES = 50_000
    const val FPS_WARMUP_MS = 4_000L
    const val FPS_WINDOW_MS = 8_000L
    const val FPS_QUICK_SETTLE_MS = 2_000L
    const val FPS_QUICK_WARMUP_MS = 1_000L
    const val FPS_QUICK_WINDOW_MS = 4_000L
    const val TILE_SETTLE_MS = 8_000L
    const val MEMORY_SAMPLE_MS = 500L
    const val LATENCY_SAMPLES = 40
    const val QUIET_MS = 250L
    const val LOAD_TIMEOUT_MS = 90_000L
    const val FRAME_TIMEOUT_MS = 30_000L
    const val GEOJSON_TIMEOUT_MS = 60_000L
    val LIBERTY = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    val CITY_TARGET = Position(longitude = -73.9857, latitude = 40.7484)
    val CITY_CAMERA = CameraPosition(target = CITY_TARGET, zoom = 16.5, tilt = 60.0, bearing = 30.0)
  }
}

private fun benchSubset(): String =
  InstrumentationRegistry.getArguments().getString("subset") ?: "all"

private fun benchRenderModes(): List<RenderOptions.RenderMode> =
  when (InstrumentationRegistry.getArguments().getString("mapMode")) {
    "texture" -> listOf(RenderOptions.RenderMode.Texture)
    "surface" -> listOf(RenderOptions.RenderMode.Surface)
    else -> listOf(RenderOptions.RenderMode.Texture, RenderOptions.RenderMode.Surface)
  }

private class BenchHost {
  var spec by mutableStateOf<BenchSpec?>(null)
  var status by mutableStateOf("Starting")
}

private class BenchSpec(
  val mode: RenderOptions.RenderMode,
  val state: BenchMapState,
  val baseStyle: BaseStyle,
  val geoJson: Boolean = false,
  val firstPosition: CameraPosition = AndroidMapBenchmark.CITY_CAMERA,
)

private class BenchMapState {
  lateinit var camera: CameraState
  var source: GeoJsonSource? = null
  val frames = FrameClock()
  @Volatile var loaded = false
  @Volatile var loadFailed: String? = null
}

@Composable
private fun BenchStatus(text: String) {
  BasicText(
    text = text,
    style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
    modifier =
      Modifier.fillMaxWidth()
        .background(Color(0xFF111111))
        .padding(horizontal = 16.dp, vertical = 14.dp),
  )
}

@Composable
private fun BenchMap(
  mode: RenderOptions.RenderMode,
  state: BenchMapState,
  baseStyle: BaseStyle,
  geoJson: Boolean = false,
  firstPosition: CameraPosition = AndroidMapBenchmark.CITY_CAMERA,
) {
  val camera = rememberCameraState(firstPosition)
  state.camera = camera
  MaplibreMap(
    modifier = Modifier.fillMaxSize(),
    baseStyle = baseStyle,
    cameraState = camera,
    options =
      MapOptions(
        gestureOptions = GestureOptions.AllDisabled,
        renderOptions = RenderOptions(preferredRenderMode = mode),
      ),
    overlay = MapOverlay.None,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    onFrame = { state.frames.record() },
    onMapLoadFinished = { state.loaded = true },
    onMapLoadFailed = { state.loadFailed = it },
  ) {
    if (geoJson) {
      val source =
        rememberGeoJsonSource(
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          options = GeoJsonOptions(synchronousUpdate = true),
        )
      state.source = source
      CircleLayer(
        id = "bench-points",
        source = source,
        color = const(Color.Red),
        radius = const(2.dp),
      )
    }
  }
}

private fun RenderOptions.RenderMode.reportName(): String =
  when (this) {
    RenderOptions.RenderMode.Texture -> "texture"
    RenderOptions.RenderMode.Surface -> "surface"
  }

private fun ComponentActivity.displayRefreshHz(): Float {
  @Suppress("DEPRECATION") val fallback = windowManager.defaultDisplay.refreshRate
  if (Build.VERSION.SDK_INT < 30) return fallback
  val rate = display?.refreshRate
  return if (rate != null && rate > 0f) rate else fallback
}

private fun ComponentActivity.runOnMainSync(action: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
