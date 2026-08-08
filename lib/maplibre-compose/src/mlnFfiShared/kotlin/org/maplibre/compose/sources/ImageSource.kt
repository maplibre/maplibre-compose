@file:JvmName("MlnFfiImageSourceKt")

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
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.PremultipliedRgba8Image
import org.maplibre.spatialk.geojson.Position

public actual class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  /**
   * The pixels this source draws, or null when a URL names them. Kept pre-converted because a style
   * change re-adds every source and the conversion would otherwise repeat on the owner thread.
   */
  private var image: PremultipliedRgba8Image?

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) : super(id) {
    bounds = position
    url = ""
    this.image = image.toPremultipliedRgba8()
  }

  public actual constructor(id: String, position: PositionQuad, uri: String) : super(id) {
    bounds = position
    url = uri
    image = null
  }

  /**
   * Adds the source with its pixels when it has them; source JSON can only name a URL, so a
   * pixel-backed source added from [toJson] would be added empty.
   */
  override fun addTo(map: MapHandle) {
    val pixels = image
    if (pixels == null) super.addTo(map)
    else map.addImageSourceImage(id, bounds.toCorners().map { it.toLatLng() }, pixels)
  }

  /** The URL form of this source; a pixel-backed source reports an empty `url` here. */
  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  /**
   * The corners MapLibre holds for this source, or null when its style has unloaded. Exists so a
   * test can assert the corner order, which MapLibre does not validate.
   */
  internal fun attachedCorners(): List<Position>? = binding.readMap { map ->
    map.imageSourceCoordinates(id)?.map { it.toPosition() }
  }

  public actual fun setBounds(bounds: PositionQuad) {
    this.bounds = bounds
    val coordinates = bounds.toCorners().map { it.toLatLng() }
    mutate { map -> map.setImageSourceCoordinates(id, coordinates) }
  }

  public actual fun setImage(image: ImageBitmap) {
    // MapLibre drops the URL when handed pixels, so the descriptor must too, or a re-add after a
    // style change would resurrect the old image.
    url = ""
    val pixels = image.toPremultipliedRgba8()
    this.image = pixels
    mutate { map -> map.setImageSourceImage(id, pixels) }
  }

  public actual fun setUri(uri: String) {
    url = uri
    // Mirror of setImage: a re-add must fetch the new URL, not re-upload the replaced pixels.
    image = null
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
