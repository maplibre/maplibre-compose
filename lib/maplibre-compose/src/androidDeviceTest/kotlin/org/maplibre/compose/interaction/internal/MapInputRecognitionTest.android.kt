package org.maplibre.compose.interaction.internal

import android.os.Build
import org.junit.Assume.assumeTrue

internal actual fun assumeTrackpadEventInjectionSupported() {
  assumeTrue("Trackpad event injection requires Android API 34+", Build.VERSION.SDK_INT >= 34)
}
