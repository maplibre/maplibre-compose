package org.maplibre.compose.sources

public actual sealed class Source {
  @Suppress("UNREACHABLE_CODE") internal abstract val impl: Nothing

  internal abstract val _sourceId: String

  internal actual val id: String get() = _sourceId

  public actual val attributionHtml: String get() = ""

  /** Returns a JSON string suitable for `map.addSourceJson()`. */
  internal abstract fun toJson(): String

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
