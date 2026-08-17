package org.maplibre.compose.sources

import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNSource
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSource

public actual sealed class Source {
  internal abstract val impl: MLNSource
  internal actual val id: String by lazy { impl.identifier }

  public actual val attributionHtml: String by lazy {
    // https://github.com/maplibre/maplibre-native/pull/3551
    @Suppress("USELESS_CAST")
    ((impl as? MLNTileSource)?.attributionHTMLString as? String?) ?: ""
  }

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
