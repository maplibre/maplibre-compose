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
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.PremultipliedRgba8Image
import org.maplibre.spatialk.geojson.Position

public actual class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  /**
   * The pixels this source draws, in the form MapLibre takes them, or null when a URL names them
   * instead.
   *
   * Kept converted rather than as an [ImageBitmap] because the descriptor is replayed: a style
   * change re-adds every source, and the conversion should not be repeated on the owner thread each
   * time. It is the same pixels a second time in memory, which is what being re-addable costs.
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
   * Adds the source with its pixels when it has them.
   *
   * Source JSON can only name a URL, so a source added from [toJson] here would be added empty and
   * would send MapLibre after the empty placeholder URL; the pixels would arrive only with the next
   * [setImage], which is a frame later and has to be re-issued after every style reload.
   */
  override fun addTo(map: MapHandle) {
    val pixels = image
    if (pixels == null) super.addTo(map)
    else map.addImageSourceImage(id, bounds.toCorners().map { it.toLatLng() }, pixels)
  }

  /**
   * The URL form of this source.
   *
   * Only [addTo] and [attributionHtml] read it, and [addTo] only for a source that has a URL, so
   * the empty `url` a pixel-backed source reports here never reaches MapLibre.
   */
  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  /**
   * The corners MapLibre holds for this source, or null when its style has unloaded.
   *
   * Nothing in the public API needs this; it exists so a test can assert that the four corners
   * arrive in the order MapLibre expects them. [addTo] builds that list itself, and a rotated or
   * mirrored quad is not something MapLibre rejects — it just draws the image wrong.
   */
  internal fun attachedCorners(): List<Position>? = binding.withMap { map ->
    map.imageSourceCoordinates(id)?.map { it.toPosition() }
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
    this.image = pixels
    mutate { map -> map.setImageSourceImage(id, pixels) }
  }

  public actual fun setUri(uri: String) {
    url = uri
    // Dropped for the mirror of the reason above: a re-add after a style change must fetch the URL
    // the caller just asked for rather than re-uploading the pixels it replaced.
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
