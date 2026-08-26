package org.maplibre.compose.testing

import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.map.GestureTarget
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/** The map runs on threads of its own, so blocking the test thread in a wait stops nothing. */
internal class MlnFfiMapFixture(val bridge: BridgeMapFixture, private val extent: MapExtent) :
  MapFixture {

  override val session: MapAdapter
    get() = bridge.core

  override val gestures: GestureTarget
    get() = bridge.session

  override val style: StyleBinding?
    get() = bridge.style

  override val events: MutableList<String>
    get() = bridge.events

  override val sourceChanges: MutableList<String?>
    get() = bridge.sourceChanges

  override val errors: MutableList<String>
    get() = bridge.errors

  override suspend fun loadStyle(style: BaseStyle, timeout: Duration) {
    bridge.loadStyle(style, timeout, extent)
  }

  override suspend fun awaitMapReady(timeout: Duration) {
    bridge.pumpUntilRendered(extent, timeout)
  }

  override suspend fun pump(frames: Int) {
    bridge.pump(frames)
  }

  override suspend fun pumpUntil(
    description: String,
    timeout: Duration,
    condition: suspend () -> Boolean,
  ) {
    bridge.pumpUntil(description, timeout, extent) { runBlocking { condition() } }
  }

  /**
   * A single frame is not enough to have put anything on the target: MapLibre skips one that is
   * throttled, or that has nothing new to draw.
   */
  override suspend fun readPixel(x: Int, y: Int): RgbaPixel {
    bridge.pumpUntilRendered(extent)
    bridge.frame(extent)
    return bridge.readPixel(x, y)
  }

  override suspend fun settle(quiet: Duration, timeout: Duration) {
    bridge.settle(quiet, timeout)
  }

  override suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration,
    block: suspend () -> T,
  ): T = bridge.awaitWhileRendering(description, timeout, block)

  override fun closeSession() {
    bridge.session.close()
  }

  override fun close() {
    bridge.close()
  }
}

internal actual fun createMapFixture(extent: MapExtent): MapFixture =
  MlnFfiMapFixture(BridgeMapFixture.create(extent), extent)

internal actual val mapLibreFlavor: MapLibreFlavor = MapLibreFlavor.NATIVE

actual typealias MapTestResult = Unit

internal actual fun runMapTest(block: suspend () -> Unit): MapTestResult = runBlocking { block() }
