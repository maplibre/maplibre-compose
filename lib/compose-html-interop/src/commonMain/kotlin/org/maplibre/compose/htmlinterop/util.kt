package org.maplibre.compose.htmlinterop

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

public expect abstract class HTMLElement {
  public fun focus()
}

internal fun Dp.toCssValue(): String = "${value}px"

@Composable internal expect fun rememberContainerNode(zIndex: String): HTMLElement

internal expect fun HTMLElement.matchLayout(layoutCoordinates: LayoutCoordinates, density: Density)

@Composable
internal expect fun <T : HTMLElement> rememberDomNode(parent: HTMLElement, factory: () -> T): T

internal expect val HTMLElement.headChild: HTMLElement?

internal expect val HTMLElement.tailChild: HTMLElement?
