package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual val undoShortcutHint: String? =
  if (Platform.osFamily == OsFamily.MACOSX) "⌘Z" else null
