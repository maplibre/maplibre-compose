package org.maplibre.compose.style

import org.maplibre.compose.mlnffi.FfiTestPlatform

internal actual fun prepareStyleNodeTestHost() {
  FfiTestPlatform.initialize()
}
