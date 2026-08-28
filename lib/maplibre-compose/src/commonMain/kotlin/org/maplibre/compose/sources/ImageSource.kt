package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Position

/** A map data source of an image placed at a given position. */
public class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  /** The pixels this source draws, or null when a URL names them. */
  private var image: ImageBitmap?

  /** Create an ImageSource from coordinates and a bitmap image. */
  public constructor(id: String, position: PositionQuad, image: ImageBitmap) : super(id) {
    bounds = position
    url = ""
    this.image = image
  }

  /** Create an ImageSource from coordinates and an image URI. */
  public constructor(id: String, position: PositionQuad, uri: String) : super(id) {
    bounds = position
    url = uri
    image = null
  }

  /**
   * Adds the source with its pixels when it has them; source JSON can only name a URL, so a
   * pixel-backed source added from [toJson] would be added empty.
   */
  override fun addTo(binding: StyleBinding): Boolean {
    val pixels = image ?: return super.addTo(binding)
    return binding.addImageSourceImage(id, bounds.toCorners(), pixels)
  }

  /** The URL form of this source; a pixel-backed source reports an empty `url` here. */
  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  /**
   * The corners MapLibre holds for this source, or null when no map owns it. Exists so a test can
   * assert the corner order, which MapLibre does not validate.
   */
  internal fun attachedCorners(): List<Position>? = map?.imageSourceCoordinates(id)

  /** Updates the latitude and longitude of the four corners of the image. */
  public fun setBounds(bounds: PositionQuad) {
    this.bounds = bounds
    map?.setImageSourceCoordinates(id, bounds.toCorners())
  }

  /** Updates the source image to a bitmap. */
  public fun setImage(image: ImageBitmap) {
    // MapLibre drops the URL when handed pixels, so the definition must too, or a re-add after a
    // style change would resurrect the old image.
    url = ""
    this.image = image
    map?.setImageSourceImage(id, image)
  }

  /** Updates the source image URI. */
  public fun setUri(uri: String) {
    url = uri
    // Mirror of setImage: a re-add must fetch the new URL, not re-upload the replaced pixels.
    image = null
    map?.setImageSourceUrl(id, uri)
  }

  internal fun applyPayload(binding: StyleBinding) {
    binding.setImageSourceCoordinates(id, bounds.toCorners())
    val pixels = image
    if (pixels != null) binding.setImageSourceImage(id, pixels)
    else if (url.isNotEmpty()) binding.setImageSourceUrl(id, url)
  }
}

/** The order MapLibre expects: top left, top right, bottom right, bottom left. */
private fun PositionQuad.toCorners(): List<Position> =
  listOf(topLeft, topRight, bottomRight, bottomLeft)

private fun Position.toCoordinateJson(): JsonArray = buildJsonArray {
  add(longitude)
  add(latitude)
}

/**
 * Remember a new [ImageSource] from the given [uri].
 *
 * Recomposition updates this source in place through [ImageSource.setUri] and
 * [ImageSource.setBounds].
 */
@Composable
public fun rememberImageSource(position: PositionQuad, uri: String): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, uri = uri) },
    update = {
      setBounds(position)
      setUri(uri)
    },
  )

/**
 * Remember a new [ImageSource] from the given [bitmap].
 *
 * Recomposition updates this source in place through [ImageSource.setImage] and
 * [ImageSource.setBounds].
 */
@Composable
public fun rememberImageSource(position: PositionQuad, bitmap: ImageBitmap): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, image = bitmap) },
    update = {
      setBounds(position)
      setImage(bitmap)
    },
  )
