package org.maplibre.compose.style

import org.maplibre.compose.logging.MapLog
import org.maplibre.nativeffi.map.MapHandle

/**
 * Loads [style] into this map with [fonts] declared in its `font-faces` object.
 *
 * The FFI exposes no font-faces setter, so registered fonts reach MapLibre Native only through the
 * document it parses. A document loaded by URL is fetched by the engine and cannot be rewritten
 * here; its registered fonts are reported and left out.
 *
 * @throws org.maplibre.nativeffi.error.MaplibreException as the style setters do.
 */
internal fun MapHandle.loadBaseStyle(
  style: BaseStyle,
  fonts: List<StyleFontDefinition>,
  logger: MapLog?,
) {
  when (style) {
    is BaseStyle.Uri -> {
      if (fonts.isNotEmpty()) {
        logger?.w {
          "MapLibre Native cannot declare registered fonts in a base style loaded by URL; " +
            "${fonts.joinToString { "'${it.name}'" }} will not be served for ${style.uri}"
        }
      }
      setStyleUrl(style.uri)
    }
    is BaseStyle.Json -> setStyleJson(mergeFontFaces(style.json, fonts).encodeToByteArray())
  }
}
