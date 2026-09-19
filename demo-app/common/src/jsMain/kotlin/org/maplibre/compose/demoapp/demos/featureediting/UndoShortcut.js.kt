package org.maplibre.compose.demoapp.demos.featureediting

import kotlinx.browser.window

internal actual val undoShortcutHint: String? =
  if (window.navigator.platform.startsWith("Mac")) "⌘Z" else "Ctrl+Z"
