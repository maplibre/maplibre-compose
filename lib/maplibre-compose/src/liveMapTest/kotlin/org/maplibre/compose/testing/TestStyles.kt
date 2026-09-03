package org.maplibre.compose.testing

/** The icon ID that [MISSING_ICON_STYLE] draws and no sprite supplies. */
internal const val MISSING_ICON_ID: String = "missing-icon"

/** A style whose one symbol layer draws [MISSING_ICON_ID] at one point. */
internal val MISSING_ICON_STYLE: String =
  """
  {
    "version": 8,
    "name": "missing icon",
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
        "layout": { "icon-image": "$MISSING_ICON_ID", "icon-allow-overlap": true }
      }
    ]
  }
  """
    .trimIndent()
