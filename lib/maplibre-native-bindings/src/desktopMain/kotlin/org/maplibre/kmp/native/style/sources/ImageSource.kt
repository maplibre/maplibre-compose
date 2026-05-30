package org.maplibre.kmp.native.style.sources

import org.maplibre.kmp.native.map.MapLibreMap

public class ImageSource(id: String) : Source(id, "image") {

  private var pendingCoordinates: DoubleArray? = null
  private var pendingImage: ImageData? = null
  private var pendingUrl: String? = null

  private class ImageData(val width: Int, val height: Int, val data: ByteArray)

  public fun setCoordinates(
    tlLat: Double,
    tlLng: Double,
    trLat: Double,
    trLng: Double,
    brLat: Double,
    brLng: Double,
    blLat: Double,
    blLng: Double,
  ) {
    val m = map
    if (m != null) {
      m.setImageSourceCoordinates(id, tlLat, tlLng, trLat, trLng, brLat, brLng, blLat, blLng)
    } else {
      pendingCoordinates = doubleArrayOf(tlLat, tlLng, trLat, trLng, brLat, brLng, blLat, blLng)
    }
  }

  public fun setImage(width: Int, height: Int, data: ByteArray) {
    val m = map
    if (m != null) {
      m.setImageSourceImage(id, width, height, data)
    } else {
      pendingImage = ImageData(width, height, data)
    }
  }

  public fun setUrl(url: String) {
    val m = map
    if (m != null) {
      m.setImageSourceUrl(id, url)
    } else {
      pendingUrl = url
    }
  }

  override fun bind(map: MapLibreMap) {
    super.bind(map)
    pendingCoordinates?.let { c ->
      map.setImageSourceCoordinates(id, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
      pendingCoordinates = null
    }
    pendingImage?.let { img ->
      map.setImageSourceImage(id, img.width, img.height, img.data)
      pendingImage = null
    }
    pendingUrl?.let { url ->
      map.setImageSourceUrl(id, url)
      pendingUrl = null
    }
  }
}
