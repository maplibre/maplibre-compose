package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.PositionQuad
import org.maplibre.kmp.js.stylespec.sources.SourceSpecification

public actual class ImageSource : Source {
  override val impl: Nothing = TODO()

  override val spec: SourceSpecification
    get() = TODO("Not yet implemented")

  override fun bind(source: org.maplibre.kmp.js.source.Source) {
    TODO("Not yet implemented")
  }

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) {}

  public actual constructor(id: String, position: PositionQuad, uri: String) {}

  public actual fun setBounds(bounds: PositionQuad) {}

  public actual fun setImage(image: ImageBitmap) {}

  public actual fun setUri(uri: String) {}
}
