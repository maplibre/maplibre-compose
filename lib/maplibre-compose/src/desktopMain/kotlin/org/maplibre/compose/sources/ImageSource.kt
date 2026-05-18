package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.maplibre.compose.util.PositionQuad
import org.maplibre.kmp.native.style.sources.ImageSource as MLNImageSource

public actual class ImageSource : Source {
  override val impl: MLNImageSource

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) : super() {
    impl = MLNImageSource(id)
    setBounds(position)
    setImage(image)
  }

  public actual constructor(id: String, position: PositionQuad, uri: String) : super() {
    impl = MLNImageSource(id)
    setBounds(position)
    setUri(uri)
  }

  public actual fun setBounds(bounds: PositionQuad) {
    impl.setCoordinates(
      tlLat = bounds.topLeft.latitude,
      tlLng = bounds.topLeft.longitude,
      trLat = bounds.topRight.latitude,
      trLng = bounds.topRight.longitude,
      brLat = bounds.bottomRight.latitude,
      brLng = bounds.bottomRight.longitude,
      blLat = bounds.bottomLeft.latitude,
      blLng = bounds.bottomLeft.longitude,
    )
  }

  public actual fun setImage(image: ImageBitmap) {
    val width = image.width
    val height = image.height
    val pixelMap = image.toPixelMap()
    val data = ByteArray(width * height * 4)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val color = pixelMap[x, y]
        val i = (y * width + x) * 4
        val a = (color.alpha * 255).toInt().toByte()
        val alpha = color.alpha
        data[i] = (color.red * alpha * 255).toInt().toByte()
        data[i + 1] = (color.green * alpha * 255).toInt().toByte()
        data[i + 2] = (color.blue * alpha * 255).toInt().toByte()
        data[i + 3] = a
      }
    }
    impl.setImage(width, height, data)
  }

  public actual fun setUri(uri: String) {
    impl.setUrl(uri)
  }
}
