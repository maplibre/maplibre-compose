package org.maplibre.compose.testing

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.map.MutableStyleImageHandle
import org.maplibre.compose.map.ResolvedStyleImage

internal suspend fun MapStyleState.setImage(
  id: String,
  image: ImageBitmap,
): MutableStyleImageHandle {
  images.set(id, ResolvedStyleImage.fromBitmap(image))
  return checkNotNull(images[id]?.asMutable)
}
