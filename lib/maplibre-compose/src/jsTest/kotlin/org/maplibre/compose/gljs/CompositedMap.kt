package org.maplibre.compose.gljs

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.js.Date
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding

private const val RENDER_TIMEOUT_MS = 30_000

internal class CompositedMap(style: BaseStyle, private val scaleFactor: Double = 1.0) :
  AutoCloseable {

  private var loadFailure: String? = null
  private var styleLoaded = false
  private val scope = MainScope()
  private val runtime = mapRuntimeForTest()
  private val state = runtime.createMapState(BaseStyle.Demo)

  var frameRequests: Int = 0
    private set

  private val session =
    GlJsMapSession(state.lifecycle, Callbacks(), logger = null, LayoutDirection.Ltr)
  private val token = state.reservePresentation()

  init {
    session.start()
    session.onSurfaceAvailable(
      object : GlJsSurfaceSession {
        override fun requestFrame() {
          frameRequests++
        }
      }
    )
    // ensureMap constructs the MapLibre map only after this session is the reserved adapter.
    state.publishPresentation(token, session)
    session.setBaseStyle(style)
  }

  /** Synchronous, so a caller can bracket it with GL of its own. */
  fun drawOnce(target: GlJsRenderTarget): Boolean {
    val rendered = session.render(GlJsFrameTarget.Composited(target), extentOf(target))
    session.markPresentationStateReplayed()
    return rendered
  }

  fun setOverdrawInspector(enabled: Boolean) {
    session.setRenderSettings(RenderOptions(isOverdrawInspectorEnabled = enabled))
  }

  suspend fun drawUntil(target: GlJsRenderTarget, what: String, condition: suspend () -> Boolean) {
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
   * style loads: MapLibre raises an `error` for a query naming a layer it lacks.
   */
  suspend fun rendersFeature(layerId: String, x: Int, y: Int): Boolean =
    styleLoaded && session.queryRenderedFeatures(DpOffset(x.dp, y.dp), setOf(layerId)).isNotEmpty()

  override fun close() {
    state.close()
    runtime.close()
    scope.cancel()
  }

  private fun extentOf(target: GlJsRenderTarget) =
    MapExtent.fromPhysical(target.widthPx, target.heightPx, scaleFactor)

  private inner class Callbacks : MapAdapter.Callbacks {
    override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
      if (style != null) {
        scope.launch { session.reconcileStyleRevision(DesiredStyleRevision.Empty) }
      }
    }

    override fun onStyleReady(map: MapAdapter) {
      styleLoaded = true
    }

    override fun onStyleFailed(map: MapAdapter, reason: String?) {
      loadFailure = reason ?: "unknown"
    }

    override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) = Unit

    override fun onEvent(map: MapAdapter, event: MapEvent) = Unit

    override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? = null

    override fun onGestureActive(map: MapAdapter, active: Boolean) = Unit

    override fun onViewportChanged(map: MapAdapter) = Unit
  }
}
