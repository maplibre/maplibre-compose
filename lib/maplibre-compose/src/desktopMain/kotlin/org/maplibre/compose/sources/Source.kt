package org.maplibre.compose.sources

import org.maplibre.kmp.native.style.sources.Source as MLNSource

public actual sealed class Source {
  internal abstract val impl: MLNSource

  internal actual val id: String
    get() = impl.id

  public actual val attributionHtml: String
    get() = impl.attribution

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
