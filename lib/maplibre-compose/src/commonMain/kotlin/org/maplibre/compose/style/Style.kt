package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions

internal interface Style {
  fun addImage(id: String, image: ImageBitmap, sdf: Boolean, resizeOptions: ImageResizeOptions?)

  fun removeImage(id: String)

  fun getSource(id: String): Source?

  fun getSources(): List<Source>

  fun addSource(source: Source)

  fun removeSource(source: Source)

  fun getLayer(id: String): Layer?

  fun getLayers(): List<Layer>

  fun addLayer(layer: Layer)

  fun addLayerAbove(id: String, layer: Layer)

  fun addLayerBelow(id: String, layer: Layer)

  fun addLayerAt(index: Int, layer: Layer)

  fun removeLayer(layer: Layer)
}
