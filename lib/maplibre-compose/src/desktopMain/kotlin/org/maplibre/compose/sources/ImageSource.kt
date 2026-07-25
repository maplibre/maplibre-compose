@file:JvmName("DesktopImageSourceKt")

package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.spatialk.geojson.Position

public actual class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  /**
   * Creates a source backed by pixels.
   *
   * Source JSON can only carry a URL, so the source is added empty and the pixels arrive with the
   * first [setImage] after attachment. `rememberImageSource` issues that call when the source is
   * created and again after every style reload, which is when the descriptor is re-added.
   *
   * TODO(maplibre-compose): attach this case through the FFI's `addImageSourceImage`, so the source
   *   is never briefly empty and MapLibre never issues a request for the empty placeholder URL.
   *   That needs [Source] to offer a per-source attachment path instead of always emitting JSON.
   */
  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) : super(id) {
    bounds = position
    url = ""
  }

  public actual constructor(id: String, position: PositionQuad, uri: String) : super(id) {
    bounds = position
    url = uri
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  public actual fun setBounds(bounds: PositionQuad) {
    this.bounds = bounds
    val coordinates = bounds.toCorners().map { it.toLatLng() }
    mutate { map -> map.setImageSourceCoordinates(id, coordinates) }
  }

  public actual fun setImage(image: ImageBitmap) {
    // MapLibre drops the URL when it is handed pixels, so the descriptor drops it too; otherwise a
    // re-add after a style change would resurrect the image the URL used to point at.
    url = ""
    val pixels = image.toPremultipliedRgba8()
    mutate { map -> map.setImageSourceImage(id, pixels) }
  }

  public actual fun setUri(uri: String) {
    url = uri
    mutate { map -> map.setImageSourceUrl(id, uri) }
  }
}

/**
 * The four corners in the order MapLibre expects them: top left, top right, bottom right, bottom
 * left.
 */
private fun PositionQuad.toCorners(): List<Position> =
  listOf(topLeft, topRight, bottomRight, bottomLeft)

private fun Position.toCoordinateJson(): JsonArray = buildJsonArray {
  add(longitude)
  add(latitude)
}
