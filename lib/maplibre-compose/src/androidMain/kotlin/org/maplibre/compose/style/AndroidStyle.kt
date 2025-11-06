package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.maplibre.android.style.sources.CustomGeometrySource
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.Source
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.ComputedSource
import org.maplibre.compose.sources.UnknownSource

internal class AndroidStyle(style: org.maplibre.android.maps.Style) : Style {
  private var impl: org.maplibre.android.maps.Style = style

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean) {
    impl.addImage(id, image.asAndroidBitmap(), sdf)
  }

  override fun removeImage(id: String) {
    impl.removeImage(id)
  }

  private fun Source.toSource() =
    when (this) {
      is VectorSource -> org.maplibre.compose.sources.VectorSource(this)
      is GeoJsonSource -> org.maplibre.compose.sources.GeoJsonSource(this)
      is RasterSource -> org.maplibre.compose.sources.RasterSource(this)
      is ImageSource -> org.maplibre.compose.sources.ImageSource(this)
      is RasterDemSource -> org.maplibre.compose.sources.RasterDemSource(this)
      is CustomGeometrySource -> ComputedSource(this)
      else -> UnknownSource(this)
    }

  override fun getSource(id: String): org.maplibre.compose.sources.Source? {
    return impl.getSource(id)?.toSource()
  }

  override fun getSources(): List<org.maplibre.compose.sources.Source> {
    return impl.sources.map { it.toSource() }
  }

  override fun addSource(source: org.maplibre.compose.sources.Source) {
    impl.addSource(source.impl)
  }

  override fun removeSource(source: org.maplibre.compose.sources.Source) {
    impl.removeSource(source.impl)
  }

  override fun getLayer(id: String): Layer? {
    return impl.getLayer(id)?.let { UnknownLayer(it) }
  }

  override fun getLayers(): List<Layer> {
    return impl.layers.map { UnknownLayer(it) }
  }

  override fun addLayer(layer: Layer) {
    impl.addLayer(layer.impl)
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    impl.addLayerAbove(layer.impl, id)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    impl.addLayerBelow(layer.impl, id)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    impl.addLayerAt(layer.impl, index)
  }

  override fun removeLayer(layer: Layer) {
    impl.removeLayer(layer.impl)
  }
}
