package org.maplibre.compose.map

import android.os.Build
import org.junit.Assume.assumeTrue

internal actual fun assumeClassifiedTrackpadInputSupported() {
  assumeTrue("Classified trackpad input requires Android API 34+", Build.VERSION.SDK_INT >= 34)
}
