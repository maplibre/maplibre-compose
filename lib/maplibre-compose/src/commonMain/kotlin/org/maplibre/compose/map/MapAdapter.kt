package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleResourceChanges
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

internal interface MapAdapter {
  /** Whether the engine remains alive after its current presentation detaches. */
  val retainsEngineBetweenPresentations: Boolean
    get() = false

  /** Identifies the presentation properties that constrain engine reuse. */
  val presentationCompatibilityKey: Any?
    get() = null

  /**
   * Ends this adapter's current presentation. An adapter that does not retain its engine closes
   * instead. Returns after the physical detachment and throws its cleanup failures.
   */
  suspend fun detachPresentation()

  /** True once [close] has been requested. Readable from any thread. */
  val isClosing: Boolean
    get() = false

  /**
   * Rejects new work at once and starts cleanup. Callable from any thread. A session adopted by a
   * [MapLifecycleAuthority] reports this with [MapLifecycleAuthority.sessionClosing].
   */
  fun close()

  suspend fun awaitClosed()

  suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation,
    guard: CameraCommandGuard? = null,
  )

  suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double?,
    bearing: Double?,
    tilt: Double?,
    animation: CameraAnimation.Ease,
    guard: CameraCommandGuard? = null,
  )

  suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  )

  fun setBaseStyle(style: BaseStyle)

  /**
   * Applies a style-composition revision. [Callbacks.onStyleReady] reports initial readiness;
   * subsequent updates preserve readiness and return only the resources they changed.
   */
  suspend fun reconcileStyleRevision(revision: StyleSnapshot): StyleResourceChanges

  fun getCameraPosition(): CameraPosition

  fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard? = null)

  fun stopCameraMovement(guard: CameraCommandGuard)

  fun setViewportInsets(insets: PaddingValues)

  suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition

  /** [geometry] has at least one position; [MapState] rejects empty input before calling. */
  suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition

  suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    guard: CameraCommandGuard?,
  )

  fun setCameraConstraints(value: CameraConstraints)

  /** The constraints last applied with [setCameraConstraints], or the defaults before any. */
  fun getCameraConstraints(): CameraConstraints

  fun getVisibleBounds(): VisibleBounds

  fun getVisibleRegion(): VisibleRegion

  /**
   * The viewport the map last adopted, with every property read from the same transform, or null
   * before the map has one. Implementations answer from where the map's size actually lands, so a
   * read made from [Callbacks.onViewportChanged] already describes a finished resize.
   */
  fun getViewport(): Viewport?

  fun setRenderSettings(value: RenderOptions)

  fun setTileLodSettings(value: TileLodOptions)

  /** Null while the map has no viewport to convert with. */
  fun positionFromScreenLocation(offset: DpOffset): Position?

  /** Null while the map has no viewport to convert with. */
  fun screenLocationFromPosition(position: Position): DpOffset?

  /** Projects overlay content using the camera of the displayed frame when available. */
  fun overlayScreenLocationFromPosition(position: Position): DpOffset? =
    screenLocationFromPosition(position)

  suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature<Geometry, JsonObject?>>

  suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature<Geometry, JsonObject?>>

  /**
   * Queries each layer in [hitPadding] on its own around [offset]: a point for zero padding,
   * otherwise a square of that radius. Returns each layer's features front first.
   */
  suspend fun queryRenderedFeaturesByLayer(
    offset: DpOffset,
    hitPadding: Map<String, Dp>,
  ): Map<String, List<Feature<Geometry, JsonObject?>>> = hitPadding.mapValues { (id, padding) ->
    if (padding == 0.dp) queryRenderedFeatures(offset, setOf(id))
    else
      queryRenderedFeatures(
        DpRect(offset.x - padding, offset.y - padding, offset.x + padding, offset.y + padding),
        setOf(id),
      )
  }

  fun metersPerDpAtLatitude(latitude: Double): Double

  /**
   * The sink that a session reports to. [onStyleChanged], [onStyleReady], [onStyleFailed], and
   * [onStyleSourcesChanged] are the style handshake: the session offers a binding, reports the
   * composition ready or a source changed for the binding it holds, and reports that a style
   * request failed. [onEvent], [onGestureActive], and [onViewportChanged] refer to no binding.
   */
  interface Callbacks {
    /** Offers the binding for a loaded style, or null when no binding is current. */
    fun onStyleChanged(map: MapAdapter, style: StyleBinding?)

    /** Reports that the base style and initial composition are ready to present. */
    fun onStyleReady(map: MapAdapter)

    /**
     * Reports that the style cannot load, either because the base style request failed or because
     * attaching the presentation threw. [reason] is the failure text when the failure carried one.
     */
    fun onStyleFailed(map: MapAdapter, reason: String?)

    /**
     * Reports that the style's sources changed. A null [sourceId] means that the adapter cannot
     * identify the changed source.
     */
    fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?)

    /** Reports one engine event whose producing identity is still current. */
    fun onEvent(map: MapAdapter, event: MapEvent)

    /**
     * Starts resolution of the style image [imageId] that the loaded style does not hold, and
     * returns the resolution for an engine that awaits it before it treats the image as missing.
     * Null means that nothing will supply the image.
     */
    fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>?

    /**
     * Reports whether a gesture holds the camera. Neither engine emits this; the session's gesture
     * token decides it.
     */
    fun onGestureActive(map: MapAdapter, active: Boolean)

    /** Reports a viewport the map adopted without a camera event, such as after a resize. */
    fun onViewportChanged(map: MapAdapter)
  }
}

internal object EmptyMapAdapterCallbacks : MapAdapter.Callbacks {
  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) = Unit

  override fun onStyleReady(map: MapAdapter) = Unit

  override fun onStyleFailed(map: MapAdapter, reason: String?) = Unit

  override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) = Unit

  override fun onEvent(map: MapAdapter, event: MapEvent) = Unit

  override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? = null

  override fun onGestureActive(map: MapAdapter, active: Boolean) = Unit

  override fun onViewportChanged(map: MapAdapter) = Unit
}

/**
 * Routes a session's reports to [owner]'s style and attachment authorities.
 *
 * [onStyleBound] runs after [owner] accepts the binding that [onStyleChanged] offers. A
 * presentation uses it to hand the binding to its style composition and resynchronize the camera; a
 * session with no presentation passes nothing.
 */
internal class MapStateCallbacks(
  private val owner: MapState,
  private val onStyleBound: (map: MapAdapter, style: StyleBinding?) -> Unit = { _, _ -> },
) : MapAdapter.Callbacks {
  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    if (owner.styleAuthority.updateLoadedStyle(map, style)) onStyleBound(map, style)
  }

  override fun onStyleReady(map: MapAdapter) {
    launchStyleRead(map) { owner.styleAuthority.markStyleReady(map) }
  }

  override fun onStyleFailed(map: MapAdapter, reason: String?) {
    owner.styleAuthority.markStyleFailed(map, reason)
  }

  override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {
    launchStyleRead(map) { owner.styleAuthority.refreshStyleSources(map, sourceId?.let(::setOf)) }
  }

  /**
   * Starts undispatched so the read claims its revision inside the engine callback, then finishes
   * on the runtime's main scope rather than a composition's, which the engine read's owner task
   * resumes on. A read that fails while its style is current marks the style failed.
   */
  private fun launchStyleRead(map: MapAdapter, read: suspend () -> Unit) {
    owner.runtime.mainScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        read()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        owner.runtime.logger?.w(error) { "Could not read the loaded style" }
        owner.styleAuthority.markStyleFailed(map, error.message)
      }
    }
  }

  override fun onEvent(map: MapAdapter, event: MapEvent) {
    owner.attachmentAuthority.onEvent(map, event)
  }

  override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? =
    owner.styleAuthority.resolveMissingImage(map, imageId)

  override fun onGestureActive(map: MapAdapter, active: Boolean) {
    owner.attachmentAuthority.setGestureActive(map, active)
  }

  override fun onViewportChanged(map: MapAdapter) {
    owner.attachmentAuthority.synchronizeCamera(map)
  }
}
