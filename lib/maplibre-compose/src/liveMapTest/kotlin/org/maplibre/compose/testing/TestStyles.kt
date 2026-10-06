package org.maplibre.compose.testing

/** The icon ID that [missingIconStyle] draws and no sprite supplies. */
internal const val MissingIconId: String = "missing-icon"

/**
 * A style whose one symbol layer draws [MissingIconId] at one point.
 *
 * [name] distinguishes one load from the next: the native fixture times out reloading identical
 * style JSON.
 */
internal fun missingIconStyle(name: String = "missing icon"): String =
  """
  {
    "version": 8,
    "name": "$name",
    "sources": {
      "points": {
        "type": "geojson",
        "data": {
          "type": "Feature",
          "geometry": { "type": "Point", "coordinates": [0, 0] },
          "properties": {}
        }
      }
    },
    "layers": [
      {
        "id": "icons",
        "type": "symbol",
        "source": "points",
        "layout": { "icon-image": "$MissingIconId", "icon-allow-overlap": true }
      }
    ]
  }
  """
    .trimIndent()
