package org.maplibre.compose.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory

/** Test-only replacement for the platform host factory, injected at the desktop boundary. */
internal val LocalMlnFfiMapHostFactory: ProvidableCompositionLocal<MlnFfiMapHostFactory?> =
  staticCompositionLocalOf {
    null
  }
