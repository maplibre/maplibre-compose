package org.maplibre.compose.expressions.dsl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.getFontResourceBytes
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FontLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue

/**
 * Returns a font stack for `textFont` (see [SymbolLayer][org.maplibre.compose.layers.SymbolLayer])
 * whose first name is served from [bytes], a TTF or OTF file.
 *
 * The file is registered with the style under [name] when a layer references the expression, and
 * released when no layer references it any more. [fallbacks] are stack names the style already
 * serves, tried for glyphs the file lacks. A registered name replaces a `font-faces` entry of the
 * base style with the same name.
 *
 * MapLibre Native reads registered fonts from the style document, so a font registered after the
 * base style loaded is used from the next base style load. MapLibre GL JS uses it at once.
 */
public fun font(
  name: String,
  bytes: ByteArray,
  fallbacks: List<String> = emptyList(),
): Expression<ListValue<StringValue>> = FontLiteral.of(name, bytes, fallbacks)

/**
 * Returns a font stack for `textFont` whose first name is served from the font [resource], as
 * [font] does for bytes.
 *
 * The resource is read asynchronously. Until it is read, and while a changed [resource] is being
 * read, the expression is the [fallbacks] stack, or leaves the property unset when there are none.
 */
@Composable
public fun rememberFont(
  name: String,
  resource: FontResource,
  fallbacks: List<String> = emptyList(),
): Expression<ListValue<StringValue>> {
  val environment = rememberResourceEnvironment()
  return rememberFont(name, fallbacks, source = resource to environment) {
    getFontResourceBytes(environment, resource)
  }
}

/** [rememberFont] over any [load], which runs again whenever [source] changes. */
@Composable
internal fun rememberFont(
  name: String,
  fallbacks: List<String>,
  source: Any,
  load: suspend () -> ByteArray,
): Expression<ListValue<StringValue>> {
  // Keyed so a changed source is pending at once, instead of keeping the previous bytes.
  val bytes: ByteArray? =
    key(source) {
      val state by produceState<ByteArray?>(null) { value = load() }
      state
    }
  return remember(name, bytes, fallbacks) {
    when {
      bytes != null -> font(name, bytes, fallbacks)
      fallbacks.isNotEmpty() -> const(fallbacks)
      else -> NullLiteral.cast()
    }
  }
}
