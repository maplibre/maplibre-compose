package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.net.URI
import org.maplibre.android.style.sources.ImageSource as MLNImageSource
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.correctedAndroidUri
import org.maplibre.compose.util.toLatLngQuad

public actual class ImageSource : Source {

  override val impl: MLNImageSource

  internal constructor(source: MLNImageSource) {
    this.impl = source
  }

  public actual constructor(
    id: String,
    position: PositionQuad,
    image: ImageBitmap,
  ) : this(MLNImageSource(id, position.toLatLngQuad(), image.asAndroidBitmap()))

  public actual constructor(
    id: String,
    position: PositionQuad,
    uri: String,
  ) : this(MLNImageSource(id, position.toLatLngQuad(), URI(uri.correctedAndroidUri())))

  public actual fun setBounds(bounds: PositionQuad) {
    impl.setCoordinates(bounds.toLatLngQuad())
  }

  public actual fun setImage(image: ImageBitmap) {
    impl.setImage(image.asAndroidBitmap())
  }

  public actual fun setUri(uri: String) {
    impl.setUri(URI(uri.correctedAndroidUri()))
  }
}
