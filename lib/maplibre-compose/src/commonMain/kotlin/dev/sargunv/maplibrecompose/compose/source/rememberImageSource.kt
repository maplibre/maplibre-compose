package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.sargunv.maplibrecompose.core.source.ImageSource
import dev.sargunv.maplibrecompose.core.util.PositionQuad

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
