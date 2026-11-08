package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.sources.RasterDemTileSource

/**
 * 3D terrain drawn from a raster DEM source. Available in the browser.
 *
 * @property source The source ID in the style.
 * @property exaggeration Multiplier for elevation. Must be finite and nonnegative; 1 uses the
 *   source's elevation without scaling.
 * @throws IllegalArgumentException if the source ID is blank or exaggeration is not finite and
 *   nonnegative.
 */
@Immutable
public data class WebTerrain
private constructor(
  public val source: String,
  public val exaggeration: Double,
  internal val managedSource: RasterDemTileSource?,
) {
  /** Uses a raster DEM source already defined by the base style. */
  public constructor(source: String, exaggeration: Double = 1.0) : this(source, exaggeration, null)

  /** Installs [source] while this terrain override is declared, even when no layer uses it. */
  public constructor(
    source: RasterDemTileSource,
    exaggeration: Double = 1.0,
  ) : this(source.id, exaggeration, source)

  init {
    require(source.isNotBlank()) { "Terrain source ID must not be blank" }
    require(exaggeration.isFinite() && exaggeration >= 0.0) {
      "Terrain exaggeration must be finite and nonnegative, was $exaggeration"
    }
  }

  internal fun toJson(): JsonObject = buildJsonObject {
    put("source", source)
    put("exaggeration", exaggeration)
  }
}
