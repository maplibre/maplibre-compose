package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject

/**
 * A video source reconstructed from a loaded style.
 *
 * This API does not construct video sources. A raster layer can still draw one that a style already
 * defines. MapLibre GL JS implements video sources; MapLibre Native does not.
 *
 * @param definition what MapLibre reports about the source.
 */
public class VideoSource internal constructor(id: String, internal val definition: JsonObject) :
  RasterLayerSource(id) {

  override fun toJson(): JsonObject = definition
}
