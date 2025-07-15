package org.maplibre.compose.style.source

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.core.source.ImageSource
import org.maplibre.compose.core.util.PositionQuad

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
