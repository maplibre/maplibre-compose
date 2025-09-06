package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.dellisd.spatialk.geojson.Position
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.kmp.native.camera.CameraOptions as MLNCameraOptions
import org.maplibre.kmp.native.util.EdgeInsets as MLNEdgeInsets
import org.maplibre.kmp.native.util.LatLng as MLNLatLng

internal fun MLNCameraOptions.toCameraPosition() =
  CameraPosition(
    target = center?.toPosition() ?: Position(0.0, 0.0),
    bearing = bearing ?: 0.0,
    tilt = pitch ?: 0.0,
    zoom = zoom ?: 0.0,
    padding = padding?.toPaddingValues() ?: PaddingValues(0.dp),
  )

internal fun CameraPosition.toMlnCameraOptions(layoutDirection: LayoutDirection) =
  MLNCameraOptions.centered(
    center = target.toMlnLatLng(),
    bearing = bearing,
    pitch = tilt,
    zoom = zoom,
    padding = padding.toMlnEdgeInsets(layoutDirection),
  )

internal fun MLNLatLng.toPosition() = Position(latitude = latitude, longitude = longitude)

internal fun Position.toMlnLatLng() = MLNLatLng(latitude = latitude, longitude = longitude)

internal fun MLNEdgeInsets.toPaddingValues() =
  // TODO: should this be density scaled?
  PaddingValues.Absolute(left = left.dp, top = top.dp, right = right.dp, bottom = bottom.dp)

internal fun PaddingValues.toMlnEdgeInsets(layoutDirection: LayoutDirection) =
  // TODO: should this be density scaled?
  MLNEdgeInsets(
    left = calculateLeftPadding(layoutDirection).value.toDouble(),
    right = calculateRightPadding(layoutDirection).value.toDouble(),
    top = calculateTopPadding().value.toDouble(),
    bottom = calculateBottomPadding().value.toDouble(),
  )
