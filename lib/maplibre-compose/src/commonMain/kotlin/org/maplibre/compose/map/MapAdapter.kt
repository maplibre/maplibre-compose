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
import org.maplibre.compose.style.StyleBinding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

internal interface MapAdapter {
  suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration)

  suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  )

  fun setBaseStyle(style: BaseStyle)

  fun getCameraPosition(): CameraPosition

  fun setCameraPosition(cameraPosition: CameraPosition)

  fun setCameraPadding(padding: PaddingValues)

  fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  )

  fun setCameraBoundingBox(boundingBox: BoundingBox?)

  fun setMaxZoom(maxZoom: Double)

  fun setMinZoom(minZoom: Double)

  fun setMinPitch(minPitch: Double)

  fun setMaxPitch(maxPitch: Double)

  /**
   * The viewport the map last adopted, with every property read from the same transform, or null
   * before the map has one. Implementations answer from the map's applied size, so a read made from
   * [Callbacks.onCameraMoved] already describes a finished resize.
   */
  fun getViewport(): Viewport?

  fun setRenderSettings(value: RenderOptions)

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

  interface Callbacks {
    /** A null [style] means the previous style unloaded and no replacement has loaded yet. */
    fun onStyleChanged(map: MapAdapter, style: StyleBinding?)

    fun onMapFinishedLoading(map: MapAdapter)

    /** A null [sourceId] means that the adapter cannot identify the changed source. */
    fun onSourceChanged(map: MapAdapter, sourceId: String?)

    fun onMapFailLoading(reason: String?)

    fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason)

    fun onCameraMoved(map: MapAdapter)

    fun onCameraMoveEnded(map: MapAdapter)

    fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onFrame(fps: Double)
  }
}
