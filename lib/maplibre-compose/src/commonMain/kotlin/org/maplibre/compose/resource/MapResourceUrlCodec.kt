package org.maplibre.compose.resource

/** Percent-encodes a resource URL so it can sit in a custom-scheme path. */
internal fun encodeResourceUrl(url: String): String {
  val out = StringBuilder(url.length)
  for (byte in url.encodeToByteArray()) {
    val value = byte.toInt() and 0xFF
    val char = value.toChar()
    if (char.isUnreserved()) out.append(char)
    else {
      out.append('%')
      out.append(value.toString(16).padStart(2, '0').uppercase())
    }
  }
  return out.toString()
}

internal fun decodeResourceUrl(encoded: String): String {
  val bytes = ArrayList<Byte>(encoded.length)
  var index = 0
  while (index < encoded.length) {
    val char = encoded[index]
    if (char == '%' && index + 2 < encoded.length) {
      val hex = encoded.substring(index + 1, index + 3)
      bytes += hex.toInt(16).toByte()
      index += 3
    } else {
      bytes += char.code.toByte()
      index += 1
    }
  }
  return bytes.toByteArray().decodeToString()
}

private fun Char.isUnreserved(): Boolean =
  this in 'a'..'z' ||
    this in 'A'..'Z' ||
    this in '0'..'9' ||
    this == '-' ||
    this == '_' ||
    this == '.' ||
    this == '~'
