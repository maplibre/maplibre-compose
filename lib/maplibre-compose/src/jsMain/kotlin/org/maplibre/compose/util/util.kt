package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.kmp.js.LngLat
import org.maplibre.kmp.js.LngLatBounds
import org.maplibre.kmp.js.PaddingOptions
import org.maplibre.kmp.js.Point
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

internal fun Alignment.toControlPosition(layoutDir: LayoutDirection): String {
  val (x, y) = align(IntSize(1, 1), IntSize(2, 2), layoutDir)
  val h =
    when (x) {
      0 -> "left"
      1 -> "right"
      else -> error("Invalid alignment")
    }
  val v =
    when (y) {
      0 -> "top"
      1 -> "bottom"
      else -> error("Invalid alignment")
    }
  return "$v-$h"
}

internal fun LngLat.toPosition() = Position(longitude = lng, latitude = lat)

internal fun Position.toLngLat() = LngLat(lng = longitude, lat = latitude)

internal fun Point.toDpOffset() = DpOffset(x = x.dp, y = y.dp)

internal fun DpOffset.toPoint() = Point(x = x.value.toDouble(), y = y.value.toDouble())

internal fun PaddingOptions.toPaddingValuesAbsolute() =
  PaddingValues.Absolute(left = left.dp, top = top.dp, right = right.dp, bottom = bottom.dp)

internal fun PaddingValues.toPaddingOptions(layoutDir: LayoutDirection) =
  PaddingOptions(
    left = calculateLeftPadding(layoutDir).value.toDouble(),
    top = calculateTopPadding().value.toDouble(),
    right = calculateRightPadding(layoutDir).value.toDouble(),
    bottom = calculateBottomPadding().value.toDouble(),
  )

internal fun LngLatBounds.toBoundingBox() =
  BoundingBox(south = getSouth(), west = getWest(), north = getNorth(), east = getEast())

internal fun BoundingBox.toLatLngBounds() =
  LngLatBounds(sw = southwest.toLngLat(), ne = northeast.toLngLat())
