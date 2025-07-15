package org.maplibre.compose.core.source

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.core.util.PositionQuad

/** A map data source of an image placed at a given position. */
public expect class ImageSource : Source {
  /** Create an ImageSource from coordinates and a bitmap image. */
  public constructor(id: String, position: PositionQuad, image: ImageBitmap)

  /** Create an ImageSource from coordinates and an image URI. */
  public constructor(id: String, position: PositionQuad, uri: String)

  /** Updates the latitude and longitude of the four corners of the image. */
  public fun setBounds(bounds: PositionQuad)

  /** Updates the source image to a bitmap. */
  public fun setImage(image: ImageBitmap)

  /** Updates the source image URI. */
  public fun setUri(uri: String)
}
