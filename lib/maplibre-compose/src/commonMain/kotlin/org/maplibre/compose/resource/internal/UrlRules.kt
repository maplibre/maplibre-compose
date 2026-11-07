package org.maplibre.compose.resource.internal

/**
 * A URL glob compiled into a linear NFA over the UTF-8 bytes of the URL.
 *
 * Matches as `CompiledGlob` in maplibre-native-ffi does, so the browser applies a rule to the same
 * URLs that MapLibre Native does: `*` matches any bytes except `/`, `**` matches any bytes, `?`
 * matches one byte except `/`, and `\` makes the next byte match only itself.
 */
internal class UrlGlob(pattern: String) {
  private val tokens: List<Token> = parse(pattern.encodeToByteArray())

  fun matches(candidate: String): Boolean {
    val states = BooleanArray(tokens.size + 1)
    states[0] = true
    for (index in tokens.indices) {
      states[index + 1] = tokens[index].kind.isStar && states[index]
    }
    for (byte in candidate.encodeToByteArray()) {
      var previous = states[0]
      states[0] = false
      for (index in tokens.indices) {
        val old = states[index + 1]
        val token = tokens[index]
        states[index + 1] =
          when (token.kind) {
            Kind.Segment,
            Kind.Spanning ->
              states[index] || (old && (token.kind == Kind.Spanning || byte != Slash))
            Kind.Any -> previous && byte != Slash
            Kind.Literal -> previous && byte == token.literal
          }
        previous = old
      }
    }
    return states[tokens.size]
  }

  private enum class Kind(val isStar: Boolean) {
    Literal(false),
    Any(false),
    Segment(true),
    Spanning(true),
  }

  private class Token(val kind: Kind, val literal: Byte = 0)

  companion object {
    /** The most tokens that MapLibre Native accepts in one pattern. */
    const val MaxTokens = 4096

    private const val Slash = '/'.code.toByte()
    private const val Star = '*'.code.toByte()
    private const val Question = '?'.code.toByte()
    private const val Backslash = '\\'.code.toByte()

    /** The number of tokens in [pattern], which MapLibre Native limits to [MaxTokens]. */
    fun tokenCount(pattern: String): Int {
      val bytes = pattern.encodeToByteArray()
      var count = 0
      var index = 0
      while (index < bytes.size) {
        if (bytes[index] == Star) {
          while (index + 1 < bytes.size && bytes[index + 1] == Star) index++
        } else if (bytes[index] == Backslash && index + 1 < bytes.size) {
          index++
        }
        count++
        index++
      }
      return count
    }

    private fun parse(bytes: ByteArray): List<Token> = buildList {
      var index = 0
      while (index < bytes.size) {
        val value = bytes[index]
        when (value) {
          Star -> {
            val start = index
            while (index + 1 < bytes.size && bytes[index + 1] == Star) index++
            add(Token(if (index == start) Kind.Segment else Kind.Spanning))
          }
          Question -> add(Token(Kind.Any))
          else -> {
            if (value == Backslash && index + 1 < bytes.size) index++
            add(Token(Kind.Literal, bytes[index]))
          }
        }
        index++
      }
    }
  }
}

/**
 * Encodes [parameters] as `name=value` pairs joined with `&`, percent-encoding every UTF-8 byte
 * except letters, digits, `-`, `.`, `_`, and `~`, as maplibre-native-ffi does.
 */
internal fun encodeQuery(parameters: Map<String, String>): String = buildString {
  parameters.forEach { (name, value) ->
    if (isNotEmpty()) append('&')
    appendPercentEncoded(name)
    append('=')
    appendPercentEncoded(value)
  }
}

/**
 * Adds [query] at the end of the query of [url] and before any fragment, keeping the parameters
 * that [url] already has, as maplibre-native-ffi does.
 */
internal fun appendQuery(url: String, query: String): String {
  if (query.isEmpty()) return url
  val fragment = url.indexOf('#').takeIf { it >= 0 } ?: url.length
  val base = url.substring(0, fragment)
  return buildString {
    append(base)
    if ('?' !in base) append('?') else if (!base.endsWith('?') && !base.endsWith('&')) append('&')
    append(query)
    append(url, fragment, url.length)
  }
}

private const val HexDigits = "0123456789ABCDEF"

private fun StringBuilder.appendPercentEncoded(text: String) {
  for (byte in text.encodeToByteArray()) {
    val value = byte.toInt() and 0xFF
    val char = value.toChar()
    if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UnreservedPunctuation) {
      append(char)
    } else {
      append('%')
      append(HexDigits[value ushr 4])
      append(HexDigits[value and 0x0F])
    }
  }
}

private const val UnreservedPunctuation = "-._~"
