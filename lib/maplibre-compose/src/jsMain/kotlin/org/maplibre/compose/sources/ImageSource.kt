package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.PositionQuad

public actual class ImageSource : Source {
  override val impl: Nothing = TODO()

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) {}

  public actual constructor(id: String, position: PositionQuad, uri: String) {}

  public actual fun setBounds(bounds: PositionQuad) {}

  public actual fun setImage(image: ImageBitmap) {}

  public actual fun setUri(uri: String) {}
}
