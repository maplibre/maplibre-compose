package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.map.ResolvedStyleImage

/** Shared image preparation, confined to the composition apply dispatcher. */
internal class StyleImageRegistry(
  private val scope: CoroutineScope,
  private val preparePainter: suspend (StyleImageRequest.Painter) -> ResolvedStyleImage,
  private val changed: () -> Unit,
) {
  private val entries = mutableMapOf<StyleImageRequest, Entry>()
  private val definitions = mutableMapOf<ResolvedStyleImage, StyleImageDefinition>()
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
      when (request) {
        is StyleImageRequest.Bitmap -> entry.definition = definition(request.prepare())
        is StyleImageRequest.Painter -> {
          val job =
            scope.launch(start = CoroutineStart.LAZY) {
              val content = preparePainter(request)
              currentCoroutineContext().ensureActive()
              entry.definition = definition(content)
              changed()
            }
          entry.job = job
          starts += job
        }
      }
    }
    // Publish callbacks may run inline. Every request must exist before any worker starts.
    starts.forEach { it.start() }
  }

  fun retain(retained: List<StyleImageDefinition>) {
    definitions.clear()
    (retained + entries.values.mapNotNull { it.definition }).forEach {
      definitions[ResolvedStyleImage(it.image, it.sdf, it.stretch)] = it
    }
  }

  fun close() {
    entries.values.forEach { it.job?.cancel() }
    entries.clear()
    definitions.clear()
  }

  private fun definition(content: ResolvedStyleImage): StyleImageDefinition =
    definitions.getOrPut(content) {
      StyleImageDefinition(ids.next(), content.pixels, content.sdf, content.stretch)
    }

  private class Entry {
    var definition: StyleImageDefinition? = null
    var job: Job? = null
  }
}
