package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
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

  /** Attaches this engine to its current presentation host. */
  suspend fun attachPresentation() = Unit

  /** Detaches this engine from its current presentation host. */
  suspend fun detachPresentation() {
    close()
    awaitClosed()
  }

  fun close()

  suspend fun awaitClosed()

  suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration)

  suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  )

  fun setBaseStyle(style: BaseStyle)

  /** Applies one complete style-composition revision and reports whether it is ready to present. */
  suspend fun reconcileStyleRevision(revision: DesiredStyleRevision): Boolean

  /** Restores a retained revision before the current composition is evaluated. */
  suspend fun replayStyleRevision(revision: DesiredStyleRevision)

  fun getCameraPosition(): CameraPosition

  fun setCameraPosition(cameraPosition: CameraPosition)

  fun setCameraPadding(padding: PaddingValues)

  fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  )

  fun setCameraConstraints(value: CameraConstraints)

  fun getVisibleBoundingBox(): BoundingBox

  fun getVisibleRegion(): VisibleRegion

  /**
   * The viewport the map last adopted, with every property read from the same transform, or null
   * before the map has one. Implementations answer from where the map's size actually lands, so a
   * read made from [Callbacks.onViewportChanged] already describes a finished resize.
   */
  fun getViewport(): Viewport?

  fun setRenderSettings(value: RenderOptions)

  fun setGestureSettings(value: GestureOptions)

  fun setTileLodSettings(value: TileLodOptions)

  /** Null while the map has no viewport to convert with. */
  fun positionFromScreenLocation(offset: DpOffset): Position?

  /** Null while the map has no viewport to convert with. */
  fun screenLocationFromPosition(position: Position): DpOffset?

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

    /** Reports that the style composition applied and is ready to present. */
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

internal class DurableStyleCallbacks(private val owner: MapState) : MapAdapter.Callbacks {
  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    owner.updateLoadedStyle(map, style)
  }

  override fun onStyleReady(map: MapAdapter) {
    owner.markStyleReady(map)
  }

  override fun onStyleFailed(map: MapAdapter, reason: String?) {
    owner.markStyleFailed(map, reason)
  }

  override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {
    owner.refreshStyleSources(map)
  }

  override fun onEvent(map: MapAdapter, event: MapEvent) {
    owner.onEvent(map, event)
  }

  override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? =
    owner.resolveMissingImage(map, imageId)

  override fun onGestureActive(map: MapAdapter, active: Boolean) {
    owner.setGestureActive(map, active)
  }

  override fun onViewportChanged(map: MapAdapter) {
    owner.synchronizeCamera(map)
  }
}
