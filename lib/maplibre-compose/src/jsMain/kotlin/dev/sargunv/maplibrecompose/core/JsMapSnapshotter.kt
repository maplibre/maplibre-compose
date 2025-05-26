package dev.sargunv.maplibrecompose.core

import androidx.compose.ui.graphics.ImageBitmap
import io.github.dellisd.spatialk.geojson.BoundingBox

internal class JsMapSnapshotter : MapSnapshotter {
  override suspend fun snapshot(
    width: Int,
    height: Int,
    styleUri: String,
    region: BoundingBox?,
    cameraPosition: CameraPosition?,
    showLogo: Boolean,
  ): ImageBitmap {
    // missing feature in MapLibre GL JS
    throw SnapshotException("Not supported")
  }
}
