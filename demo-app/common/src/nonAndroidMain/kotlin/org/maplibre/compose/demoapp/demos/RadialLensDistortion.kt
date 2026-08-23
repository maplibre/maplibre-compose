package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

@Composable
actual fun Modifier.radialLensDistortion(sizePx: Float): Modifier {
  val effect =
    remember(sizePx) {
      val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(LensShader))
      builder.uniform("size", sizePx, sizePx)
      ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
    }
  return graphicsLayer { renderEffect = effect }
}
