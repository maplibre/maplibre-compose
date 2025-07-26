package org.maplibre.compose.sources

import MapLibre.MLNImageSource
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.toMLNCoordinateQuad
import org.maplibre.compose.util.toUIImage
import platform.Foundation.NSURL

public actual class ImageSource : Source {
  override val impl: MLNImageSource

  internal constructor(source: MLNImageSource) {
    this.impl = source
  }

  public actual constructor(
    id: String,
    position: PositionQuad,
    image: ImageBitmap,
  ) : this(
    MLNImageSource(
      identifier = id,
      coordinateQuad = position.toMLNCoordinateQuad(),
      image = image.toUIImage(),
    )
  )

  public actual constructor(
    id: String,
    position: PositionQuad,
    uri: String,
  ) : this(
    MLNImageSource(
      identifier = id,
      coordinateQuad = position.toMLNCoordinateQuad(),
      URL = NSURL(string = uri),
    )
  )

  public actual fun setBounds(bounds: PositionQuad) {
    impl.setCoordinates(bounds.toMLNCoordinateQuad())
  }

  public actual fun setImage(image: ImageBitmap) {
    impl.setImage(image.toUIImage())
  }

  public actual fun setUri(uri: String) {
    impl.setURL(NSURL(string = uri))
  }
}
