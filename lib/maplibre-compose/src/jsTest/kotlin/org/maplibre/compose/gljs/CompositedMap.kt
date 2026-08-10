package org.maplibre.compose.gljs

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.js.Date
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.spatialk.geojson.Position

private const val RENDER_TIMEOUT_MS = 30_000

/** One GL JS map on the shared GPU context, with no Compose in the way. */
internal class CompositedMap(style: BaseStyle) : AutoCloseable {

  private var loadFailure: String? = null
  private var styleLoaded = false

  var frameRequests: Int = 0
    private set

  private val session = GlJsMapSession(Callbacks(), logger = null, LayoutDirection.Ltr)

  init {
    session.onSurfaceAvailable(
      object : GlJsSurfaceSession {
        override fun requestFrame() {
          frameRequests++
        }
      }
    )
    session.setBaseStyle(style)
  }

  /** Synchronous, so a caller can bracket it with GL of its own. */
  fun drawOnce(target: GlJsRenderTarget): Boolean =
    session.render(GlJsFrameTarget.Composited(target), extentOf(target))

  /** A frame count is no wait: the session redraws whether or not the style's sources arrived. */
  suspend fun drawUntil(target: GlJsRenderTarget, what: String, condition: () -> Boolean) {
    val deadline = Date.now() + RENDER_TIMEOUT_MS
    while (!condition()) {
      drawOnce(target)
      loadFailure?.let { error("the style failed to load: $it") }
      check(Date.now() < deadline) { "timed out waiting for $what" }
      yieldToBrowser()
    }
    check(drawOnce(target)) { "the map drew no frame once $what" }
  }

  /**
   * Whether [layerId] is in the render tree, not merely the stylesheet. Never asked before the
   * style loads: MapLibre answers a query naming a layer it lacks with an `error`.
   */
  fun rendersFeature(layerId: String, x: Int, y: Int): Boolean =
    styleLoaded && session.queryRenderedFeatures(DpOffset(x.dp, y.dp), setOf(layerId)).isNotEmpty()

  override fun close() = session.close()

  private fun extentOf(target: GlJsRenderTarget) =
    MapExtent.fromPhysical(target.widthPx, target.heightPx, 1.0)

  private inner class Callbacks : MapAdapter.Callbacks {
    override fun onStyleChanged(map: MapAdapter, style: Style?) = Unit

    override fun onMapFinishedLoading(map: MapAdapter) {
      styleLoaded = true
    }

    override fun onMapFailLoading(reason: String?) {
      loadFailure = reason ?: "unknown"
    }

    override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) = Unit

    override fun onCameraMoved(map: MapAdapter) = Unit

    override fun onCameraMoveEnded(map: MapAdapter) = Unit

    override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

    override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

    override fun onFrame(fps: Double) = Unit
  }
}
