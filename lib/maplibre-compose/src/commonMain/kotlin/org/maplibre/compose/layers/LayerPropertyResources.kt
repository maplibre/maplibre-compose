package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FontLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.style.ImageManager
import org.maplibre.compose.style.ImageManager.BitmapKey
import org.maplibre.compose.style.ImageManager.PainterKey
import org.maplibre.compose.style.StyleNode

/** The images and fonts one layer property references, resolved to their style identifiers. */
internal class LayerPropertyResources(
  private val bitmaps: Map<BitmapKey, String>,
  private val painters: Map<PainterKey, String>,
  private val fonts: Map<FontLiteral, List<String>>,
  private val density: Density,
  private val layoutDirection: LayoutDirection,
) {
  fun resolve(bitmap: BitmapLiteral): String = bitmaps.getValue(bitmap.imageKey())

  fun resolve(painter: PainterLiteral): String =
    painters.getValue(painter.imageKey(density, layoutDirection))

  fun resolve(font: FontLiteral): List<String> = fonts.getValue(font)
}

@Composable
internal fun rememberLayerPropertyResources(
  expression: Expression<*>,
  styleNode: StyleNode,
  density: Density,
  layoutDirection: LayoutDirection,
): LayerPropertyResources? {
  val manager = styleNode.imageManager
  val (bitmapKeys, painterKeys, fontLiterals) =
    remember(expression, density, layoutDirection) {
      val bitmaps = mutableSetOf<BitmapKey>()
      val painters = mutableSetOf<PainterKey>()
      val fonts = mutableSetOf<FontLiteral>()
      expression.visit {
        when (it) {
          is BitmapLiteral -> bitmaps.add(it.imageKey())
          is PainterLiteral -> painters.add(it.imageKey(density, layoutDirection))
          is FontLiteral -> fonts.add(it)
          else -> Unit
        }
      }
      Triple(bitmaps, painters, fonts)
    }
  // Expressions can change every animation frame while their image inputs stay the same.
  val painters = rememberPainterImages(manager, painterKeys)
  if (painters == null) {
    // Snapshot evaluators must wait until the property has compiled with its resolved image IDs.
    DisposableEffect(styleNode) {
      styleNode.beginResourcePreparation()
      onDispose { styleNode.endResourcePreparation() }
    }
    return null
  }
  val bitmaps = remember(manager, bitmapKeys) { bitmapKeys.associateWith(manager::acquireBitmap) }
  DisposableEffect(manager, bitmaps) {
    onDispose { bitmaps.keys.forEach(manager::releaseBitmap) }
  }
  val fontManager = styleNode.fontManager
  val fonts =
    remember(fontManager, fontLiterals) { fontLiterals.associateWith(fontManager::acquire) }
  DisposableEffect(fontManager, fonts) { onDispose { fonts.keys.forEach(fontManager::release) } }
  return remember(bitmaps, painters, fonts, density, layoutDirection) {
    LayerPropertyResources(bitmaps, painters, fonts, density, layoutDirection)
  }
}

@Composable
private fun rememberPainterImages(
  manager: ImageManager,
  painters: Set<PainterKey>,
): Map<PainterKey, String>? {
  if (painters.isEmpty()) return emptyMap()
  val graphicsContext = LocalGraphicsContext.current
  // Reset to pending immediately when inputs change, instead of exposing the previous IDs.
  return key(manager, painters, graphicsContext) {
    val images by
      produceState<Map<PainterKey, String>?>(null) {
        val acquired = mutableMapOf<PainterKey, String>()
        try {
          for (painter in painters) {
            acquired[painter] = manager.acquirePainter(painter, graphicsContext)
          }
          value = acquired
          awaitCancellation()
        } finally {
          acquired.keys.forEach(manager::releasePainter)
        }
      }
    images
  }
}

private fun BitmapLiteral.imageKey() = BitmapKey(value, sdf, stretch)

private fun PainterLiteral.imageKey(density: Density, layoutDirection: LayoutDirection) =
  PainterKey(value, density, layoutDirection, size, sdf, stretch, alpha, colorFilter)
