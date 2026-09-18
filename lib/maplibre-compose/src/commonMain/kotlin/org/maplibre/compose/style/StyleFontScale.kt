package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.value.FloatValue

internal const val INTERNAL_GLOBAL_STATE_PREFIX = "maplibre-compose:"
internal const val FONT_SCALE_GLOBAL_STATE = "${INTERNAL_GLOBAL_STATE_PREFIX}font-scale"
internal val LocalStyleFontScale = compositionLocalOf<Float?> { null }
private val globalFontScale = globalState(FONT_SCALE_GLOBAL_STATE).asNumber(const(1f))

@Composable
internal fun styleFontScale(): Expression<FloatValue> {
  val fontScale = LocalDensity.current.fontScale
  // A nested density provider can give one group of layers its own accessibility scale.
  return if (fontScale == LocalStyleFontScale.current) globalFontScale else const(fontScale)
}
