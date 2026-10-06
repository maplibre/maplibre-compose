package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.value.FloatValue

internal const val InternalGlobalStatePrefix = "maplibre-compose:"
internal const val FontScaleGlobalState = "${InternalGlobalStatePrefix}font-scale"
internal val LocalStyleFontScale = compositionLocalOf<Float?> { null }
private val globalFontScale = globalState(FontScaleGlobalState).asNumber(const(1f))

@Composable
internal fun styleFontScale(): Expression<FloatValue> {
  val fontScale = LocalDensity.current.fontScale
  // A nested density provider can give one group of layers its own accessibility scale.
  return if (fontScale == LocalStyleFontScale.current) globalFontScale else const(fontScale)
}
