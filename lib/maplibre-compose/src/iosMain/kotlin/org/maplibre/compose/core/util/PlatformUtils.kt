package org.maplibre.compose.core.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.uikit.LocalUIViewController

public actual object PlatformUtils {
  @Composable
  public actual fun getSystemRefreshRate(): Float {
    return LocalUIViewController.current.view.window?.screen?.maximumFramesPerSecond?.toFloat()
      ?: 0f
  }
}
