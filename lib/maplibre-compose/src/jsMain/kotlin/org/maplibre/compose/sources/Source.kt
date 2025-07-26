package org.maplibre.compose.sources

public actual sealed class Source {
  internal abstract val impl: Nothing

  internal actual val id: String
    get() = TODO()

  public actual val attributionHtml: String
    get() = TODO()

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
