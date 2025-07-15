package org.maplibre.compose.htmlinterop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Density
import kotlinx.browser.document

public actual typealias HTMLElement = org.w3c.dom.HTMLElement

@Composable
internal actual fun rememberContainerNode(zIndex: String): HTMLElement =
  rememberDomNode(parent = document.body!!) {
    document.createElement("div").unsafeCast<HTMLElement>().apply {
      style.position = "absolute"
      style.margin = "0px"
      style.zIndex = zIndex
    }
  }

internal actual fun HTMLElement.matchLayout(
  layoutCoordinates: LayoutCoordinates,
  density: Density,
) {
  with(density) {
    style.apply {
      val rect = layoutCoordinates.boundsInWindow()
      width = rect.width.toDp().toCssValue()
      height = rect.height.toDp().toCssValue()
      left = rect.left.toDp().toCssValue()
      top = rect.top.toDp().toCssValue()
    }
  }
}

@Composable
internal actual fun <T : HTMLElement> rememberDomNode(parent: HTMLElement, factory: () -> T): T {
  return remember(key1 = parent, calculation = factory).also { child ->
    DisposableEffect(parent, child) {
      parent.appendChild(child)
      onDispose { parent.removeChild(child) }
    }
  }
}

internal actual val HTMLElement.headChild
  get() = firstElementChild?.unsafeCast<HTMLElement>()

internal actual val HTMLElement.tailChild
  get() = lastElementChild?.unsafeCast<HTMLElement>()
