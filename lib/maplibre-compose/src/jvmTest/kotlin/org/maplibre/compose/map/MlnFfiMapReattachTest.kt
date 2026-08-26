@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * The session leaves and re-enters the composition against the same [MapState]: the engine's core,
 * its loaded style, and the camera all survive the detach.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapReattachTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun reattaching_a_session_reuses_the_live_core_and_its_loaded_style() = runFfiComposeUiTest {
    MlnFfiApplication.configure(runtimeOptions)
    val frames = AtomicInt(0)
    val errors = mutableListOf<String>()
    var loadsFinished = 0
    var attached by mutableStateOf(true)
    val firstPosition =
      CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 5.0)
    lateinit var state: MapState

    setContent {
      val factory = remember { MultiUseTestMapHostFactory() }
      CompositionLocalProvider(LocalMlnFfiMapHostFactory provides factory) {
        val mapState =
          rememberMapState(cameraPosition = firstPosition, baseStyle = STYLE) {
            FillLayer(
              id = "user-fill",
              source =
                rememberGeoJsonSource(
                  data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>())
                ),
              color = const(Color.Red),
            )
          }
        state = mapState
        if (attached) {
          Box(Modifier.fillMaxSize()) {
            MaplibreMap(
              state = mapState,
              modifier = Modifier.fillMaxSize(),
              logger = remember { Logger.withTag("reattach-test") },
              onFrame = { frames.incrementAndFetch() },
              onMapLoadFailed = { errors += "mapLoadFailed: $it" },
              onMapLoadFinished = { loadsFinished++ },
            )
          }
        }
      }
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { loadsFinished > 0 && frames.load() > 0 }
    val engine = state.engine as MlnFfiMapEngine
    val core = requireNotNull(engine.core) { "no core after the first attach" }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      "user-fill" in core.currentStyleLayerIds()
    }

    runOnUiThread { attached = false }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { state.attachedAdapter == null }
    assertSame(core, engine.core, "the core must survive the session detach")
    val loadsBeforeReattach = loadsFinished
    val framesBeforeReattach = frames.load()

    runOnUiThread { attached = true }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.attachedAdapter != null && frames.load() > framesBeforeReattach
    }

    assertSame(core, engine.core, "a re-attach must reuse the live core")
    assertSame(core, state.attachedAdapter, "the camera must rewire to the same core")
    assertEquals(loadsBeforeReattach, loadsFinished, "a re-attach must not reload the style")
    assertTrue(
      "user-fill" in core.currentStyleLayerIds(),
      "the composed layer must still be in the loaded style",
    )
    val camera = core.getCameraPosition()
    assertEquals(firstPosition.target.longitude, camera.target.longitude, 1e-4, "longitude")
    assertEquals(firstPosition.target.latitude, camera.target.latitude, 1e-4, "latitude")
    assertEquals(firstPosition.zoom, camera.zoom, 1e-4, "zoom")
    assertTrue(errors.isEmpty(), "the cycle reported errors: $errors")
  }

  /** The production Desktop test bridge is one-shot; a re-entering surface needs a fresh driver. */
  private class MultiUseTestMapHostFactory : MlnFfiMapHostFactory {
    override val bridges: List<RenderBackendPair> =
      listOf(
        when (Maplibre.supportedRenderBackends().singleOrNull()) {
          RenderBackend.METAL ->
            RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
          RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.VULKAN, composeBackend())
          else -> error("No multi-use Desktop test bridge for this runtime")
        }
      )

    override val description: String = "multi-use ${bridges.single()} test bridge"

    override fun create(backends: RenderBackendPair): MlnFfiMapHostResult =
      MlnFfiMapHostResult.Created(FfiTestPlatform.createRenderDriver())

    private fun composeBackend(): ComposeRenderBackend =
      if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        ComposeRenderBackend.DIRECT3D12
      } else {
        ComposeRenderBackend.OPENGL
      }
  }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
