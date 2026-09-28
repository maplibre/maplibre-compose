package org.maplibre.compose.testing

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.map.MutableStyleImageHandle
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.util.PreparedImage

internal suspend fun MapStyleState.setImage(
  id: String,
  image: ImageBitmap,
): MutableStyleImageHandle {
  images.set(id, ResolvedStyleImage(PreparedImage.fromBitmap(image)))
  return checkNotNull(images[id]?.asMutable)
}
