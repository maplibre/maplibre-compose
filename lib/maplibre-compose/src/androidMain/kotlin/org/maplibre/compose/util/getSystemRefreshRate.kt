package org.maplibre.compose.util

import android.content.Context
import android.os.Build
import android.view.WindowManager

internal fun getSystemRefreshRate(context: Context): Float {
  val display =
    if (Build.VERSION.SDK_INT >= 30) context.display
    else
      @Suppress("DEPRECATION")
      (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
  return display?.refreshRate ?: 0f
}
