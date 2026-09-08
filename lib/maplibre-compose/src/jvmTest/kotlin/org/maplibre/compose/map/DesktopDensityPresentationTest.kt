package org.maplibre.compose.map

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class DesktopDensityPresentationTest {
  @Test
  fun pixel_density_round_trip_replaces_engines_and_font_scale_keeps_the_attachment() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    try {
      runFfiComposeUiTest {
        val options = MapRuntimeOptions(cacheFile = cacheFile)
        val runtime = createMapRuntime(options)
        var density by mutableStateOf(Density(2f))
        val frames = AtomicInteger()
        val observedDensity = AtomicReference<Density>()
        val styleEffectStarts = AtomicInteger()
        val camera = CameraPosition(zoom = 4.0)
        val state =
          runtime.createMapState(BaseStyle.Empty, cameraPosition = camera) {
            val current = LocalDensity.current
            SideEffect { observedDensity.set(current) }
            DisposableEffect(Unit) {
              styleEffectStarts.incrementAndGet()
              onDispose {}
            }
            BackgroundLayer(
              "density-${current.density}-${current.fontScale}",
              color = const(Color.Red),
            )
          }
        try {
          setFfiTestMapContent(options, presentationCount = 3) {
            LaunchedEffect(state) {
              state.events.collect { if (it is MapEvent.FrameRendered) frames.incrementAndGet() }
            }
            CompositionLocalProvider(LocalDensity provides density) {
              MaplibreMap(state = state, modifier = Modifier.size(128.dp))
            }
          }
          waitUntil(timeoutMillis = 10_000) {
            state.currentMapAttachment != null &&
              state.style.loadState == StyleLoadState.Ready &&
              frames.get() > 0
          }
          val firstAttachment = requireNotNull(state.currentMapAttachment)
          val firstEngine = firstAttachment.adapter
          val beforeReplacement = frames.get()
          runOnIdle { density = Density(1f) }
          waitUntil(timeoutMillis = 10_000) {
            val current = state.currentMapAttachment
            current != null &&
              current !== firstAttachment &&
              state.style.loadState == StyleLoadState.Ready &&
              frames.get() > beforeReplacement
          }
          val replacement = requireNotNull(state.currentMapAttachment)
          assertNotSame(firstEngine, replacement.adapter)
          assertFalse(firstAttachment.isValid)
          firstEngine.awaitClosed()
          val retainedCamera = state.cameraPosition
          assertEquals(camera.bearing, retainedCamera.bearing, 1e-4)
          assertEquals(camera.tilt, retainedCamera.tilt, 1e-4)
          assertEquals(camera.zoom, retainedCamera.zoom, 1e-4)
          assertEquals(camera.target.longitude, retainedCamera.target.longitude, 1e-4)
          assertEquals(camera.target.latitude, retainedCamera.target.latitude, 1e-4)
          waitUntil(timeoutMillis = 10_000) {
            "density-1.0-1.0" in (replacement.adapter as MlnFfiMapSession).currentStyleLayerIds()
          }

          val beforeReturn = frames.get()
          runOnIdle { density = Density(2f) }
          waitUntil(timeoutMillis = 10_000) {
            val current = state.currentMapAttachment
            current != null &&
              current !== replacement &&
              state.style.loadState == StyleLoadState.Ready &&
              frames.get() > beforeReturn
          }
          val returned = requireNotNull(state.currentMapAttachment)
          assertNotSame(firstEngine, returned.adapter)
          assertNotSame(replacement.adapter, returned.adapter)
          assertFalse(replacement.isValid)
          replacement.adapter.awaitClosed()
          waitUntil(timeoutMillis = 10_000) {
            "density-2.0-1.0" in (returned.adapter as MlnFfiMapSession).currentStyleLayerIds()
          }

          // Font scale changes declarative content but not native engine compatibility.
          val effectsBeforeFontScale = styleEffectStarts.get()
          runOnIdle { density = Density(2f, fontScale = 1.5f) }
          waitUntil(timeoutMillis = 10_000) {
            observedDensity.get()?.fontScale == 1.5f
          }
          waitUntil(timeoutMillis = 10_000) {
            val layers = (returned.adapter as MlnFfiMapSession).currentStyleLayerIds()
            "density-2.0-1.5" in layers && "density-2.0-1.0" !in layers
          }
          assertSame(returned, state.currentMapAttachment)
          assertEquals(
            effectsBeforeFontScale,
            styleEffectStarts.get(),
            "Style effects restarted for a font-scale change",
          )
          val changedCamera = camera.copy(zoom = 5.0)
          val beforeCamera = frames.get()
          runOnIdle { state.setCameraPosition(changedCamera) }
          waitUntil(timeoutMillis = 10_000) {
            abs(state.cameraPosition.zoom - changedCamera.zoom) < 1e-4 &&
              frames.get() > beforeCamera
          }
        } finally {
          runtime.close()
          runtime.awaitClosed()
        }
      }
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
