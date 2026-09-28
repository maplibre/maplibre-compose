package org.maplibre.compose.util

/** Reports release failures after every independent resource has had a chance to close. */
internal fun List<Throwable>.throwCleanupFailures() {
  val first = firstOrNull() ?: return
  drop(1).filter { it !== first }.forEach(first::addSuppressed)
  throw first
}
