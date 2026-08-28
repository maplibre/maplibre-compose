package org.maplibre.compose.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember

/**
 * Remembers [create]'s value, running [onAbandoned] when Compose abandons the composition that
 * created it. An abandoned composition runs no [androidx.compose.runtime.DisposableEffect], so a
 * resource allocated in a remember initializer must release itself through this path.
 */
@Composable
internal fun <T : Any> rememberAbandonable(
  vararg keys: Any?,
  onAbandoned: (T) -> Unit,
  create: () -> T,
): T =
  remember(*keys) {
      object : RememberObserver {
        val value = create()

        override fun onRemembered() {}

        override fun onForgotten() {}

        override fun onAbandoned() {
          onAbandoned(value)
        }
      }
    }
    .value
