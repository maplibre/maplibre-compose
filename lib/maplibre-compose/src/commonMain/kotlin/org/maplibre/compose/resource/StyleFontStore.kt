package org.maplibre.compose.resource

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.compose.style.FONT_URL_SCHEME
import org.maplibre.compose.style.FontFile
import org.maplibre.compose.style.isFontUrl

/**
 * Serves registered font files to the engines of one runtime by their content-addressed URLs.
 *
 * Each owner, a loaded style or an engine session, holds the set of files its style document or
 * `font-faces` property refers to. A file stays available while any owner holds it, so two maps
 * that register the same bytes share one entry, and one map dropping its files never removes a file
 * another map still refers to.
 */
internal class StyleFontStore {
  private val lock = reentrantLock()
  private val files = mutableMapOf<String, FontFile>()
  private val holders = mutableMapOf<String, MutableSet<Any>>()
  private val held = mutableMapOf<Any, Set<FontFile>>()

  val provider: MapResourceProvider =
    MapResourceProvider(
      accepts = { request -> isFontUrl(request.url) },
      load = { request ->
        bytes(request.url)?.let { MapResourceLoad.Bytes(it) }
          ?: MapResourceLoad.Failed(
            MapResourceError.NotFound,
            "No registered font is served at ${request.url}",
          )
      },
    )

  /** Replaces the files [owner] holds with [fonts]. */
  fun hold(owner: Any, fonts: Collection<FontFile>) {
    lock.withLock {
      val next = fonts.toSet()
      val previous = held[owner].orEmpty()
      next.forEach { file ->
        files.getOrPut(file.contentId) { file }
        holders.getOrPut(file.contentId, ::mutableSetOf).add(owner)
      }
      (previous - next).forEach { releaseLocked(owner, it) }
      if (next.isEmpty()) held.remove(owner) else held[owner] = next
    }
  }

  /** Releases every file [owner] holds. */
  fun drop(owner: Any) {
    lock.withLock { held.remove(owner)?.forEach { releaseLocked(owner, it) } }
  }

  fun bytes(url: String): ByteArray? = lock.withLock {
    files[url.removePrefix("$FONT_URL_SCHEME://")]?.bytes
  }

  private fun releaseLocked(owner: Any, file: FontFile) {
    val remaining = holders[file.contentId] ?: return
    remaining.remove(owner)
    if (remaining.isEmpty()) {
      holders.remove(file.contentId)
      files.remove(file.contentId)
    }
  }
}
