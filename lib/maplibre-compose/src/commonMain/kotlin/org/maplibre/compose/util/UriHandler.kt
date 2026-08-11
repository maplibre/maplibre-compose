package org.maplibre.compose.util

import androidx.compose.ui.platform.UriHandler

/**
 * Try to open given URL in browser. Returns `false` if given [uri] is invalid and/or can't be
 * handled by the system (e.g. if no browser is installed).
 */
public fun UriHandler.tryOpenUri(uri: String): Boolean =
  try {
    openUri(uri)
    true
  } catch (_: IllegalArgumentException) {
    false
  }
