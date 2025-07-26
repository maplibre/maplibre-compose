package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.PositionQuad

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

/** Remember a new [ImageSource] from the given [uri]. */
@Composable
public fun rememberImageSource(position: PositionQuad, uri: String): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, uri = uri) },
    update = {
      setBounds(position)
      setUri(uri)
    },
  )

/** Remember a new [ImageSource] from the given [bitmap]. */
@Composable
public fun rememberImageSource(position: PositionQuad, bitmap: ImageBitmap): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, image = bitmap) },
    update = {
      setBounds(position)
      setImage(bitmap)
    },
  )
