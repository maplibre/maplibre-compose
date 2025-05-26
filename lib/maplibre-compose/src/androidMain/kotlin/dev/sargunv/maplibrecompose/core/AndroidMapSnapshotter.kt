package dev.sargunv.maplibrecompose.core

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.sargunv.maplibrecompose.core.util.correctedAndroidUri
import dev.sargunv.maplibrecompose.core.util.toLatLngBounds
import dev.sargunv.maplibrecompose.core.util.toMLNCameraPosition
import io.github.dellisd.spatialk.geojson.BoundingBox
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter as MLNMapSnapshotter

internal class AndroidMapSnapshotter(
  private val context: Context,
  internal var layoutDir: LayoutDirection,
  internal var density: Density,
) : MapSnapshotter {

  override suspend fun snapshot(
    width: Int,
    height: Int,
    styleUri: String,
    region: BoundingBox?,
    cameraPosition: CameraPosition?,
    showLogo: Boolean,
  ): ImageBitmap {
    val styleBuilder = Style.Builder().fromUri(styleUri.correctedAndroidUri())
    val options =
      MLNMapSnapshotter.Options(width, height)
        .withStyleBuilder(styleBuilder)
        .withRegion(region?.toLatLngBounds())
        .withCameraPosition(cameraPosition?.toMLNCameraPosition(density, layoutDir))
        .withLogo(showLogo)

    val snapshotter = MLNMapSnapshotter(context, options)

    return suspendCancellableCoroutine { cont ->
      snapshotter.start({ snapshot -> cont.resume(snapshot.bitmap.asImageBitmap()) }) { error ->
        cont.resumeWithException(SnapshotException(error))
      }
      cont.invokeOnCancellation { snapshotter.cancel() }
    }
  }
}
