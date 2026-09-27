package org.maplibre.compose.testing

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.map.MutableStyleImageHandle
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.MutableGeoJsonSourceHandle
import org.maplibre.compose.sources.MutableImageSourceHandle
import org.maplibre.compose.sources.MutableSourceHandle
import org.maplibre.compose.sources.Source

/**
 * Installs a fixture before the behavior under test starts. Command ordering has separate tests.
 */
internal suspend fun MapStyleState.addSource(source: Source): MutableSourceHandle {
  sources.add(source)
  awaitCommands()
  return checkNotNull(sources[source]?.asMutable)
}

internal suspend fun MapStyleState.addSource(source: GeoJsonSource): MutableGeoJsonSourceHandle =
  addSource(source as Source) as MutableGeoJsonSourceHandle

internal suspend fun MapStyleState.addSource(source: ImageSource): MutableImageSourceHandle =
  addSource(source as Source) as MutableImageSourceHandle

internal suspend fun MapStyleState.setImage(
  id: String,
  image: ImageBitmap,
): MutableStyleImageHandle {
  images.set(id, ResolvedStyleImage.fromBitmap(image))
  return checkNotNull(images[id]?.asMutable)
}
