package org.maplibre.compose.map

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class HoverLayer(val id: String, val identity: Any, val handler: (HoverEvent) -> Unit)

/** A pass captures registrations and style identity, while delivery reads surviving handlers. */
internal class HoverScene(
  val mapIdentity: Any,
  val identity: Any,
  val layers: List<HoverLayer>,
  val isValid: () -> Boolean,
  val query: suspend (HoverLayer, DpOffset) -> Boolean,
)

/** Direct map observation and one frame-paced worker for layer membership. Never consumes input. */
internal class MapHoverGesture(
  private val scope: CoroutineScope,
  private val target: GestureTarget,
  private val interactions: MapInteractionTarget,
  private val options: () -> MapGestures,
  private val ids: GestureIds,
  private val density: Density,
  private val awaitFrame: suspend () -> Unit = { withFrameNanos {} },
) {
  private data class Entered(val sample: GesturePointerSample, val handler: (HoverEvent) -> Unit)

  private var location: GesturePointerSample? = null
  private var gestureId: Long? = null
  private var sceneIdentity: Any? = null
  private var mapIdentity: Any? = null
  private var bindingEntry: Entered? = null
  private val layers = mutableMapOf<Any, Entered>()
  private var registrations = emptySet<Any>()
  private var pending: GesturePointerSample? = null
  private var worker: Job? = null
  private var epoch = 0L

  init {
    scope.launch {
      snapshotFlow { listOf(interactions.hoverRevision, options().binding("hover")) }
        .collect { refresh() }
    }
  }

  fun onPointerEvent(event: PointerEvent) {
    if (event.type == PointerEventType.Exit || event.changes.any { it.pressed }) {
      exit()
      return
    }
    if (event.type == PointerEventType.Scroll) return
    val change = event.changes.firstOrNull { it.type in hoverTypes } ?: return
    val sample = event.gestureSample(0, target, density, change.position, setOf(change.type))
    if (sample.buttons.isNotEmpty()) {
      exit()
      return
    }
    move(sample)
  }

  internal fun move(sample: GesturePointerSample) {
    location = sample
    refresh()
  }

  /** Snapshot reads here are deliberately outside the flow; only presentation changes resample. */
  private fun refresh() {
    val raw = location ?: return
    val scene = interactions.captureHover()
    val binding = options().binding("hover")
    if (
      scene == null ||
        !scene.isValid() ||
        !binding.matches(raw, contact = false) ||
        (binding.handlers.hover == null && scene.layers.isEmpty())
    ) {
      clear()
      return
    }
    if (sceneIdentity != scene.identity) {
      invalidateWorker()
      val generation = epoch
      exitLayers()
      if (epoch != generation || location !== raw || !scene.isValid()) return
      sceneIdentity = scene.identity
    }
    val currentIds = scene.layers.mapTo(mutableSetOf()) { it.identity }
    if (registrations.any { it !in currentIds }) invalidateWorker()
    registrations = currentIds
    val generation = epoch
    fun valid() = generation == epoch && location === raw && scene.isValid()
    if (mapIdentity != scene.mapIdentity) {
      val old = bindingEntry
      bindingEntry = null
      old?.handler?.invoke(HoverEvent.Exit(old.sample))
      if (!valid()) return
      mapIdentity = scene.mapIdentity
    }
    val removed = layers.keys.filter { it !in currentIds }
    val exits = removed.mapNotNull { layers.remove(it) }
    deliverExits(exits)
    if (!valid()) return
    scene.layers.forEach { layer ->
      layers[layer.identity]?.let { layers[layer.identity] = it.copy(handler = layer.handler) }
    }
    val sample =
      raw.copy(
        gestureId = gestureId ?: ids.next().also { gestureId = it },
        position = target.positionFromScreenLocation(raw.screenOffset),
      )
    observe(bindingEntry, sample, options().binding("hover").handlers.hover) { bindingEntry = it }
    if (!valid()) return
    if (scene.layers.isEmpty()) {
      pending = null
      return
    }
    pending = sample
    startWorker()
  }

  private fun observe(
    previous: Entered?,
    sample: GesturePointerSample,
    handler: ((HoverEvent) -> Unit)?,
    set: (Entered?) -> Unit,
  ) {
    val next = handler?.let { Entered(sample, it) }
    set(next)
    if (handler == null) previous?.handler?.invoke(HoverEvent.Exit(previous.sample))
    else handler(if (previous == null) HoverEvent.Enter(sample) else HoverEvent.Move(sample))
  }

  private fun startWorker() {
    if (worker != null) return
    lateinit var job: Job
    job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          while (pending != null) {
            awaitFrame()
            val sample = pending ?: break
            pending = null
            val scene = interactions.captureHover() ?: continue
            val generation = epoch
            val hits = mutableMapOf<Any, Boolean>()
            try {
              for (layer in scene.layers) {
                if (generation != epoch || !scene.isValid()) break
                hits[layer.identity] = scene.query(layer, sample.screenOffset)
              }
            } catch (cancelled: CancellationException) {
              currentCoroutineContext().ensureActive()
              if (generation == epoch && scene.isValid()) throw cancelled
            }
            if (generation == epoch && scene.isValid()) publish(scene, sample, hits, generation)
          }
        } finally {
          if (worker === job) {
            worker = null
            if (pending != null) startWorker()
          }
        }
      }
    worker = job
    job.start()
  }

  private fun publish(
    captured: HoverScene,
    sample: GesturePointerSample,
    hits: Map<Any, Boolean>,
    generation: Long,
  ) {
    val current = interactions.captureHover() ?: return
    if (!current.isValid() || current.identity != captured.identity) return
    for (layer in captured.layers) {
      if (generation != epoch || !current.isValid()) return
      val surviving = current.layers.firstOrNull { it.identity == layer.identity } ?: continue
      val previous = layers[surviving.identity]
      if (hits[surviving.identity] == true) {
        layers[surviving.identity] = Entered(sample, surviving.handler)
        surviving.handler(
          if (previous == null) HoverEvent.Enter(sample) else HoverEvent.Move(sample)
        )
      } else if (previous != null) {
        layers.remove(surviving.identity)
        surviving.handler(HoverEvent.Exit(sample))
      }
    }
  }

  fun exit() {
    location = null
    clear()
  }

  private fun invalidateWorker() {
    epoch++
    pending = null
    worker?.cancel()
  }

  private fun exitLayers() {
    val exits = layers.values.toList()
    layers.clear()
    deliverExits(exits)
  }

  private fun clear() {
    invalidateWorker()
    val exits = listOfNotNull(bindingEntry) + layers.values
    bindingEntry = null
    layers.clear()
    registrations = emptySet()
    gestureId = null
    sceneIdentity = null
    mapIdentity = null
    deliverExits(exits)
  }

  private fun deliverExits(exits: List<Entered>) {
    var failure: Throwable? = null
    for (entry in exits) {
      try {
        entry.handler(HoverEvent.Exit(entry.sample))
      } catch (cause: Throwable) {
        if (failure == null) failure = cause else checkNotNull(failure).addSuppressed(cause)
      }
    }
    failure?.let { throw it }
  }

  companion object {
    private val hoverTypes = setOf(PointerType.Mouse, PointerType.Stylus, PointerType.Eraser)
  }
}
