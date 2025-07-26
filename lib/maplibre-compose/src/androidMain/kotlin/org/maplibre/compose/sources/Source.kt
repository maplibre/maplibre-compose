package org.maplibre.compose.sources

import org.maplibre.android.style.sources.Source as MLNSource

public actual sealed class Source {
  internal abstract val impl: MLNSource

  internal actual val id: String by lazy { impl.id }

  public actual val attributionHtml: String by lazy { impl.attribution }

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
