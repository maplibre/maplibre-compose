package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.nativeffi.query.FeatureStateSelector
import org.maplibre.nativeffi.render.RenderSessionHandle

/** The feature identity that MapLibre Native uses for state. */
private data class FeatureStateKey(
  val sourceId: String,
  val sourceLayerId: String?,
  val featureId: String,
)

/**
 * The feature state that belongs to one loaded style.
 *
 * MapLibre Native stores these values on its renderer. This copy accepts operations while no
 * renderer exists and restores the values whenever the renderer is replaced.
 */
internal class MlnFfiFeatureStateStore {
  private val lock = MlnFfiLock()
  private val states = mutableMapOf<FeatureStateKey, JsonObject>()

  fun set(sourceId: String, sourceLayerId: String?, featureId: String, state: JsonObject) {
    lock.withLock {
      val key = FeatureStateKey(sourceId, sourceLayerId, featureId)
      val merged = JsonObject(states[key].orEmpty() + state)
      if (merged.isEmpty()) states.remove(key) else states[key] = merged
    }
  }

  fun get(sourceId: String, sourceLayerId: String?, featureId: String): JsonObject =
    lock.withLock { states[FeatureStateKey(sourceId, sourceLayerId, featureId)] }
      ?: JsonObject(emptyMap())

  fun remove(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    lock.withLock {
      val key = FeatureStateKey(sourceId, sourceLayerId, featureId)
      if (stateKey == null) {
        states.remove(key)
      } else {
        val remaining = states[key].orEmpty() - stateKey
        if (remaining.isEmpty()) states.remove(key) else states[key] = JsonObject(remaining)
      }
    }
  }

  fun reset(sourceId: String, sourceLayerId: String?) {
    lock.withLock {
      states.keys.removeAll { key ->
        key.sourceId == sourceId && (sourceLayerId == null || key.sourceLayerId == sourceLayerId)
      }
    }
  }

  fun forgetSource(sourceId: String) {
    lock.withLock { states.keys.removeAll { key -> key.sourceId == sourceId } }
  }

  /** Restores a snapshot, so native calls run without holding [lock]. */
  fun replay(session: RenderSessionHandle): Boolean {
    val snapshot = lock.withLock { states.toList() }
    snapshot.forEach { (key, state) ->
      session.setFeatureState(
        featureStateSelector(key.sourceId, key.sourceLayerId, key.featureId),
        state.toJsonBytes(),
      )
    }
    return snapshot.isNotEmpty()
  }
}

internal fun MlnFfiStyleBinding.forgetFeatureStates(sourceId: String) {
  featureStateStore?.forgetSource(sourceId)
}

internal fun MlnFfiStyleBinding.liveFeatureStateStore(): MlnFfiFeatureStateStore? =
  featureStateStore?.takeIf {
    isLoaded
  }

/** The retained copy is already updated; apply it to the renderer when one is ready. */
internal fun MlnFfiStyleBinding.mutateLiveFeatureState(action: (RenderSessionHandle) -> Unit) {
  withRenderSession(action) ?: return
  mutateMap {}
}

internal fun featureStateSelector(
  sourceId: String,
  sourceLayerId: String? = null,
  featureId: String? = null,
  stateKey: String? = null,
) =
  FeatureStateSelector(sourceId).apply {
    this.sourceLayerId = sourceLayerId
    this.featureId = featureId
    this.stateKey = stateKey
  }
