package org.maplibre.compose.util

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.LngLatBounds
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.map.CameraPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

// MapLibre GL JS works in CSS pixels, the same units as Compose's dp; scaling by density here would
// double-apply it.

internal fun LngLat.toPosition(): Position = Position(longitude = lng, latitude = lat)

internal fun Position.toLngLat(): LngLat = LngLat(lng = longitude, lat = latitude)

/** A plain `{x, y}`. `Point.convert` accepts this; `queryRenderedFeatures` does not. */
internal fun DpOffset.toPoint(): Point = unsafeJso {
  x = this@toPoint.x.value.toDouble()
  y = this@toPoint.y.value.toDouble()
}

internal fun Point.toDpOffset(): DpOffset = DpOffset(x.dp, y.dp)

internal fun LngLatBounds.toBoundingBox(): BoundingBox =
  BoundingBox(southwest = getSouthWest().toPosition(), northeast = getNorthEast().toPosition())

internal fun LngLatBounds.toVisibleBounds(): VisibleBounds =
  VisibleBounds(southwest = getSouthWest().toPosition(), northeast = getNorthEast().toPosition())

internal fun BoundingBox.toLngLatBounds(): LngLatBounds =
  LngLatBounds(sw = southwest.toLngLat(), ne = northeast.toLngLat())

internal fun DpPadding.toPaddingOptions(): PaddingOptions =
  (CameraPadding() + this).toPaddingOptions()

internal fun PaddingOptions.toCameraPadding(): CameraPadding =
  CameraPadding(top, left, bottom, right)

internal fun CameraPadding.toPaddingOptions(): PaddingOptions = unsafeJso {
  top = this@toPaddingOptions.top
  left = this@toPaddingOptions.left
  bottom = this@toPaddingOptions.bottom
  right = this@toPaddingOptions.right
}
