package org.maplibre.compose.testing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest

@OptIn(ExperimentalTestApi::class)
internal fun runGraphicsTest(block: suspend (GraphicsContext) -> Unit) = runComposeUiTest {
  var completed = false
  setContent {
    val graphicsContext = LocalGraphicsContext.current
    LaunchedEffect(Unit) {
      block(graphicsContext)
      completed = true
    }
  }
  waitUntil { completed }
}
