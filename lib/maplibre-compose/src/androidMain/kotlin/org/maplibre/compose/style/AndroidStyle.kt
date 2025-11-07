package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import org.maplibre.android.maps.ImageContent
import org.maplibre.android.maps.ImageStretches
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
import org.maplibre.compose.util.ImageResizeOptions

internal class AndroidStyle(
  style: org.maplibre.android.maps.Style,
  private val getDensity: () -> Density,
) : Style {
  private var impl: org.maplibre.android.maps.Style = style

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    val androidBitmap = image.asAndroidBitmap()
    if (resizeOptions == null) impl.addImage(id, androidBitmap, sdf)
    else {
      with(getDensity()) {
        val left = resizeOptions.left.toPx()
        val top = resizeOptions.top.toPx()
        val right = androidBitmap.width - resizeOptions.right.toPx()
        val bottom = androidBitmap.height - resizeOptions.bottom.toPx()
        impl.addImage(
          id,
          androidBitmap,
          sdf,
          listOf(ImageStretches(left, right)),
          listOf(ImageStretches(top, bottom)),
          ImageContent(left, top, right, bottom),
        )
      }
    }
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
