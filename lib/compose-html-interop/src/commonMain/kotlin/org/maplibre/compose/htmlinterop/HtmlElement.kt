package org.maplibre.compose.htmlinterop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

@Composable
public fun <T : HTMLElement> HtmlElement(
  factory: () -> T,
  update: (T) -> Unit = {},
  zIndex: String = "auto",
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val container = rememberContainerNode(zIndex)
  val child = rememberDomNode(parent = container, factory = factory)
  SnapshotEffect(child) { update(it) }
  Box(
    modifier
      .fillMaxSize()
      .onGloballyPositioned { container.matchLayout(it, density) }
      .drawBehind { drawRect(color = Color.Transparent, size = size, blendMode = BlendMode.Clear) }
  )
  HtmlFocusAdapter(container)
}
