package org.maplibre.compose.demoapp.demos

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

@Composable
actual fun Modifier.radialLensDistortion(sizePx: Float): Modifier {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this

  val effect =
    remember(sizePx) {
      val shader = RuntimeShader(LensShader)
      shader.setFloatUniform("size", sizePx, sizePx)
      RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
  return graphicsLayer { renderEffect = effect }
}
