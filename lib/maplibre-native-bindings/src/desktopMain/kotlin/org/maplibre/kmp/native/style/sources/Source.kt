package org.maplibre.kmp.native.style.sources

import org.maplibre.kmp.native.map.MapLibreMap

public open class Source(public val id: String, public val type: String) {
  internal var map: MapLibreMap? = null

  public val attribution: String = ""

  public open fun configJson(): String? = null

  public open fun bind(map: MapLibreMap) {
    this.map = map
  }

  public open fun unbind() {
    map = null
  }
}

/** Encodes a string as a JSON string literal with proper escaping. */
internal fun jsonString(value: String): String = buildString {
  append('"')
  for (ch in value) {
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else ->
        if (ch.code < 0x20) {
          append("\\u${ch.code.toString(16).padStart(4, '0')}")
        } else {
          append(ch)
        }
    }
  }
  append('"')
}
