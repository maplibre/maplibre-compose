package org.maplibre.compose.style

import MapLibre.MLNComputedShapeSource
import MapLibre.MLNImageSource
import MapLibre.MLNRasterDEMSource
import MapLibre.MLNRasterTileSource
import MapLibre.MLNShapeSource
import MapLibre.MLNSource
import MapLibre.MLNStyle
import MapLibre.MLNStyleLayer
import MapLibre.MLNVectorTileSource
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.ComputedSource
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.RasterDemSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.util.toUIImage

internal class IosStyle(style: MLNStyle, private val getScale: () -> Float) : Style {
  private var impl: MLNStyle = style

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean) {
    impl.setImage(image.toUIImage(getScale(), sdf), forName = id)
  }

  override fun removeImage(id: String) {
    impl.removeImageForName(id)
  }

  private fun MLNSource.toSource() =
    when (this) {
      is MLNVectorTileSource -> VectorSource(this)
      is MLNShapeSource -> GeoJsonSource(this)
      is MLNRasterTileSource -> RasterSource(this)
      is MLNImageSource -> ImageSource(this)
      is MLNRasterDEMSource -> RasterDemSource(this)
      is MLNComputedShapeSource -> ComputedSource(this)
      else -> UnknownSource(this)
    }

  override fun getSource(id: String): Source? {
    return impl.sourceWithIdentifier(id)?.toSource()
  }

  override fun getSources(): List<Source> {
    return impl.sources.map { (it as MLNSource).toSource() }
  }

  override fun addSource(source: Source) {
    impl.addSource(source.impl)
  }

  override fun removeSource(source: Source) {
    impl.removeSource(source.impl)
  }

  override fun getLayer(id: String): Layer? {
    return impl.layerWithIdentifier(id)?.let { UnknownLayer(it) }
  }

  override fun getLayers(): List<Layer> {
    return impl.layers.map { UnknownLayer(it as MLNStyleLayer) }
  }

  override fun addLayer(layer: Layer) {
    impl.addLayer(layer.impl)
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    impl.insertLayer(layer.impl, aboveLayer = impl.layerWithIdentifier(id)!!)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    impl.insertLayer(layer.impl, belowLayer = impl.layerWithIdentifier(id)!!)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    impl.insertLayer(layer.impl, atIndex = index.toULong())
  }

  override fun removeLayer(layer: Layer) {
    impl.removeLayer(layer.impl)
  }
}
