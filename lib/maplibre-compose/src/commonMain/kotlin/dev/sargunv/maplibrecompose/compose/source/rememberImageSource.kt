package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.ImageBitmap
import dev.sargunv.maplibrecompose.core.source.ImageSource
import dev.sargunv.maplibrecompose.core.util.PositionQuad

/**
 * Remember a new [ImageSource] with the given [id] from the given [uri].
 *
 * @throws IllegalArgumentException if a source with the given [id] already exists.
 */
@Composable
public fun rememberImageSource(id: String, position: PositionQuad, uri: String): ImageSource =
  key(id, uri) {
    rememberUserSource(
      factory = { ImageSource(id = id, position = position, uri = uri) },
      update = {
        setBounds(position)
        setUri(uri)
      },
    )
  }

/**
 * Remember a new [ImageSource] with the given [id] from the given [bitmap].
 *
 * @throws IllegalArgumentException if a source with the given [id] already exists.
 */
@Composable
public fun rememberImageSource(
  id: String,
  position: PositionQuad,
  bitmap: ImageBitmap,
): ImageSource =
  key(id, bitmap) {
    rememberUserSource(
      factory = { ImageSource(id = id, position = position, image = bitmap) },
      update = {
        setBounds(position)
        setImage(bitmap)
      },
    )
  }
