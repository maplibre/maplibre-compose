package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.ImageBitmap
import dev.sargunv.maplibrecompose.core.source.ImageBoundingBox
import dev.sargunv.maplibrecompose.core.source.ImageSource
import dev.sargunv.maplibrecompose.core.source.ImageSourceData

/**
 * Remember a new [ImageSource] with the given [id] from the given [uri].
 *
 * @throws IllegalArgumentException if a layer with the given [id] already exists.
 */
@Composable
public fun rememberImageSource(
  id: String,
  boundingBox: ImageBoundingBox,
  uri: String,
): ImageSource =
  key(id, uri) {
    rememberUserSource(
      factory = { ImageSource(id = id, data = ImageSourceData.Uri(boundingBox, uri)) },
      update = {},
    )
  }

@Composable
public fun rememberImageSource(
  id: String,
  boundingBox: ImageBoundingBox,
  bitmap: ImageBitmap,
): ImageSource =
  key(id, bitmap) {
    rememberUserSource(
      factory = { ImageSource(id = id, data = ImageSourceData.Bitmap(boundingBox, bitmap)) },
      update = {},
    )
  }
