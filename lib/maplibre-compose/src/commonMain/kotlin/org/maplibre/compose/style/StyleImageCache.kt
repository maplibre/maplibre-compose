package org.maplibre.compose.style

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.util.ImageStretch

/** Preparation is shared; only committed image nodes determine resource ownership. */
internal class StyleImageCache {
  private val ids = IncrementingId("image")
  private var committed = emptySet<Request>()
  private val requests = mutableMapOf<Any, Request>()
  private val images = mutableMapOf<Content, StyleImageDefinition>()

  fun bitmap(key: Any, prepare: () -> Content): Request =
    requests.getOrPut(key) {
      Request(intern(prepare()), ::abandon) { error("Bitmap already prepared") }
    }

  fun painter(key: Any, prepare: suspend () -> Content): Request =
    requests.getOrPut(key) { Request(null, ::abandon) { intern(prepare()) } }

  private fun intern(content: Content): StyleImageDefinition =
    images.getOrPut(content) {
      StyleImageDefinition(ids.next(), content.image, content.sdf, content.stretch)
    }

  fun retain(nodes: List<StyleImageNode>) {
    committed = nodes.mapNotNull { it.request }.toSet()
    requests.values.retainAll(committed)
    pruneImages()
  }

  private fun abandon(request: Request) {
    if (request in committed) return
    requests.values.removeAll { it === request }
    pruneImages()
  }

  private fun pruneImages() {
    val ids = requests.values.mapNotNull { it.definition?.id }.toSet()
    images.values.removeAll { it.id !in ids }
  }

  fun clear() {
    committed = emptySet()
    requests.clear()
    images.clear()
  }

  data class Content(val image: ImageSnapshot, val sdf: Boolean, val stretch: ImageStretch?)

  class Request(
    definition: StyleImageDefinition?,
    private val abandon: (Request) -> Unit,
    private val prepare: suspend () -> StyleImageDefinition,
  ) : RememberObserver {
    // Successful applies prune from the committed tree. Abandoned remembers have no apply.
    override fun onAbandoned() = abandon(this)

    override fun onRemembered() = Unit

    override fun onForgotten() = Unit

    var definition: StyleImageDefinition? = definition
      private set

    private val mutex = Mutex()

    // Each committed property waits in its own effect. Cancelling one waiter never releases an
    // image another property uses. If preparation is cancelled, the next waiter can retry it.
    suspend fun resolve(): StyleImageDefinition = mutex.withLock {
      definition ?: prepare().also { definition = it }
    }
  }
}
