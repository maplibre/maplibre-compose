package org.maplibre.compose.mlnffi

import java.net.ServerSocket

internal actual fun unusedLoopbackPort(): Int = ServerSocket(0).use { it.localPort }
