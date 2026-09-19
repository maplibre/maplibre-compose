package org.maplibre.compose.demoapp.demos.featureediting

internal actual val undoShortcutHint: String? =
  if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) "⌘Z"
  else "Ctrl+Z"
