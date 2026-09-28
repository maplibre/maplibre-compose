package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.util.EnginePixels
import org.maplibre.compose.util.ImageStretch

/** Shared image preparation, confined to the composition apply dispatcher. */
internal class StyleImageRegistry(
  private val scope: CoroutineScope,
  private val prepare: suspend (StyleImageRequest) -> ResolvedStyleImage,
  private val changed: () -> Unit,
) {
  private val entries = mutableMapOf<StyleImageRequest, Entry>()
  // Keyed by content, so painters that draw identical pixels share one style image.
  private val definitions = mutableMapOf<Content, StyleImageDefinition>()
  private val ids = IncrementingId("image")

  val pending: Boolean
    get() = entries.values.any { it.definition == null }

  val resolved: Map<StyleImageRequest, StyleImageDefinition>
    get() =
      entries.mapNotNull { (request, entry) -> entry.definition?.let { request to it } }.toMap()

  fun update(required: Set<StyleImageRequest>, retained: List<StyleImageDefinition>) {
    entries.keys.filter { it !in required }.forEach { entries.remove(it)?.job?.cancel() }
    retain(retained)
    val starts = mutableListOf<Job>()
    required.forEach { request ->
      if (request in entries) return@forEach
      val entry = Entry()
      entries[request] = entry
      val job =
        scope.launch(start = CoroutineStart.LAZY) {
          val content = prepare(request)
          currentCoroutineContext().ensureActive()
          entry.definition = definition(content)
          changed()
        }
      entry.job = job
      starts += job
    }
    // Publish callbacks may run inline. Every request must exist before any worker starts.
    starts.forEach { it.start() }
  }

  fun retain(retained: List<StyleImageDefinition>) {
    definitions.clear()
    (retained + entries.values.mapNotNull { it.definition }).forEach {
      definitions[Content(it.image.pixels, it.sdf, it.stretch)] = it
    }
  }

  fun close() {
    entries.values.forEach { it.job?.cancel() }
    entries.clear()
    definitions.clear()
  }

  private fun definition(content: ResolvedStyleImage): StyleImageDefinition =
    definitions.getOrPut(Content(content.image.pixels, content.sdf, content.stretch)) {
      StyleImageDefinition(ids.next(), content.image, content.sdf, content.stretch)
    }

  private data class Content(val pixels: EnginePixels, val sdf: Boolean, val stretch: ImageStretch?)

  private class Entry {
    var definition: StyleImageDefinition? = null
    var job: Job? = null
  }
}
