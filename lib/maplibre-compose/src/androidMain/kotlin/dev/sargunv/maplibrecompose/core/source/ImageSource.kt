package dev.sargunv.maplibrecompose.core.source

import androidx.compose.ui.graphics.asAndroidBitmap
import dev.sargunv.maplibrecompose.core.util.toLatLng
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import java.net.URI
import org.maplibre.android.style.sources.ImageSource as MLNImageSource

public actual class ImageSource : Source {

  override val impl: MLNImageSource

  internal constructor(source: MLNImageSource) {
    impl = source
  }

  public actual constructor(id: String, data: ImageSourceData) {
    impl = when (data) {
      is ImageSourceData.Bitmap -> MLNImageSource(id, data.boundingBox.toLatLngQuad(), bitmap = data.bitmap.asAndroidBitmap())
      is ImageSourceData.Uri -> MLNImageSource(id, data.boundingBox.toLatLngQuad(), uri = URI(data.uri))
    }
  }
}

private fun ImageBoundingBox.toLatLngQuad() = LatLngQuad(
  topRight = this.topRight.toLatLng(),
  topLeft = this.topLeft.toLatLng(),
  bottomLeft = this.bottomLeft.toLatLng(),
  bottomRight = this.bottomRight.toLatLng()
)

private fun Corner.toLatLng() = LatLng(
  latitude = latitude,
  longitude = longitude
)
