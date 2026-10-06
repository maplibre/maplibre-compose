package org.maplibre.compose.util

/**
 * Formats a `toString` result the way a data class does: `TypeName(name=value, name=value)`.
 *
 * Callers leave out secrets such as access tokens and request headers.
 */
internal fun formatToString(typeName: String, vararg fields: Pair<String, Any?>): String =
  fields.joinToString(prefix = "$typeName(", postfix = ")") { (name, value) -> "$name=$value" }
