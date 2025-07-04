package dev.sargunv.maplibrecompose.core.util

import platform.UIKit.UIView

internal fun getSystemRefreshRate(view: UIView): Long {
  return view.window?.screen?.maximumFramesPerSecond ?: 0L
}
