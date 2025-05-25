package dev.sargunv.maplibrecompose.core

import androidx.compose.ui.graphics.ImageBitmap
import cocoapods.MapLibre.MLNMapCamera
import cocoapods.MapLibre.MLNMapSnapshotOptions
import cocoapods.MapLibre.MLNMapSnapshotter
import dev.sargunv.maplibrecompose.core.util.toImageBitmap
import dev.sargunv.maplibrecompose.core.util.toMLNCoordinateBounds
import dev.sargunv.maplibrecompose.core.util.toMLNMapCamera
import io.github.dellisd.spatialk.geojson.BoundingBox
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL

internal class IosMapSnapshotter : MapSnapshotter {
  override suspend fun snapshot(
    width: Int,
    height: Int,
    styleUri: String,
    region: BoundingBox?,
    cameraPosition: CameraPosition?,
    showLogo: Boolean,
  ): ImageBitmap {
    val size = CGSizeMake(width.toDouble(), height.toDouble())
    val options =
      MLNMapSnapshotOptions(
        styleURL = NSURL(string = styleUri),
        camera = cameraPosition?.toMLNMapCamera(size) ?: MLNMapCamera(),
        size = size,
      )

    cameraPosition?.zoom?.let { options.zoomLevel = it }
    region?.toMLNCoordinateBounds()?.let { options.coordinateBounds = it }
    options.showsLogo = showLogo

    val snapshotter = MLNMapSnapshotter(options)

    return suspendCancellableCoroutine { cont ->
      snapshotter.startWithCompletionHandler { snapshot, error ->
        if (snapshot != null) {
          cont.resume(snapshot.image.toImageBitmap())
        } else {
          cont.resumeWithException(SnapshotException(error?.description ?: "Unknown error"))
        }
      }
      cont.invokeOnCancellation { snapshotter.cancel() }
    }
  }
}
