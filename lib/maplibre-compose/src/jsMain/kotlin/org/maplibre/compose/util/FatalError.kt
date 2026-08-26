package org.maplibre.compose.util

/** Kotlin/JS defines no fatal error class; the runtime aborts on its own. */
internal actual fun rethrowIfFatal(error: Throwable) = Unit
