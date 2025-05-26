package dev.sargunv.maplibrecompose.core

import androidx.compose.ui.graphics.ImageBitmap
import io.github.dellisd.spatialk.geojson.BoundingBox

internal interface MapSnapshotter {
  suspend fun snapshot(
    width: Int,
    height: Int,
    styleUri: String,
    region: BoundingBox?,
    cameraPosition: CameraPosition?,
    showLogo: Boolean,
  ): ImageBitmap
}
