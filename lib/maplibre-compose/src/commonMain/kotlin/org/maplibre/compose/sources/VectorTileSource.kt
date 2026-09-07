package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A map data source of tiled vector data. */
public class VectorTileSource : VectorSource {

  private val json: JsonObject

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   */
  public constructor(id: String, uri: String) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      put("url", uri)
    }
  }

  /**
   * @param id Unique identifier for this source
   * @param tiles List of URIs pointing to tile images
   * @param options see [TileSetOptions]
   */
  public constructor(id: String, tiles: List<String>, options: TileSetOptions) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      putTileSetOptions(options)
    }
  }

  internal constructor(id: String, definition: JsonObject) : super(id) {
    json = definition
  }

  override fun toJson(): JsonObject = json
}

/** Remember a new [VectorTileSource] from the given [uri]. */
@Composable
public fun rememberVectorTileSource(uri: String): VectorTileSource =
  key(uri) { rememberUserSource(factory = { VectorTileSource(id = it, uri = uri) }, update = {}) }

@Composable
public fun rememberVectorTileSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
): VectorTileSource =
  key(tiles, options) {
    rememberUserSource(
      factory = { VectorTileSource(id = it, tiles = tiles, options = options) },
      update = {},
    )
  }
