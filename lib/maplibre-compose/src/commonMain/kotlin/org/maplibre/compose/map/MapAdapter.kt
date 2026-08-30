package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
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

  suspend fun animateCameraPosition(
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

  fun setCameraPosition(
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
   * read made from [Callbacks.onCameraMoved] already describes a finished resize.
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

  interface Callbacks {
    fun onStyleChanged(map: MapAdapter, style: StyleBinding?)

    fun onMapFinishedLoading(map: MapAdapter)

    /** A null [sourceId] means that the adapter cannot identify the changed source. */
    fun onSourceChanged(map: MapAdapter, sourceId: String?)

    fun onMapFailLoading(map: MapAdapter, reason: String?)

    fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason)

    fun onCameraMoved(map: MapAdapter)

    fun onCameraMoveEnded(map: MapAdapter)

    fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onFrame(fps: Double)
  }
}

internal object EmptyMapAdapterCallbacks : MapAdapter.Callbacks {
  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) = Unit

  override fun onMapFinishedLoading(map: MapAdapter) = Unit

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) = Unit

  override fun onMapFailLoading(map: MapAdapter, reason: String?) = Unit

  override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) = Unit

  override fun onCameraMoved(map: MapAdapter) = Unit

  override fun onCameraMoveEnded(map: MapAdapter) = Unit

  override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

  override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

  override fun onFrame(fps: Double) = Unit
}

internal class DurableStyleCallbacks(private val owner: MapState) : MapAdapter.Callbacks {
  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) = Unit

  override fun onMapFinishedLoading(map: MapAdapter) {
    owner.markStyleReady(map)
  }

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) = Unit

  override fun onMapFailLoading(map: MapAdapter, reason: String?) {
    owner.markStyleFailed(map, reason)
  }

  override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) = Unit

  override fun onCameraMoved(map: MapAdapter) = Unit

  override fun onCameraMoveEnded(map: MapAdapter) = Unit

  override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

  override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) = Unit

  override fun onFrame(fps: Double) = Unit
}
