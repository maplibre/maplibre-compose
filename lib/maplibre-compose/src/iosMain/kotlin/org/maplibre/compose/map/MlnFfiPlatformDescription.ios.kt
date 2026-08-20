package org.maplibre.compose.map

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.UIKit.UIDevice

internal actual val mlnFfiOperatingSystem: String
  get() = UIDevice.currentDevice.systemName

@OptIn(ExperimentalNativeApi::class)
internal actual val mlnFfiArchitecture: String
  get() = Platform.cpuArchitecture.name
