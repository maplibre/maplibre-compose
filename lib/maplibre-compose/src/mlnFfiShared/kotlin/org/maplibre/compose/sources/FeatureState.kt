package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.query.FeatureStateSelector
import org.maplibre.nativeffi.render.RenderSessionHandle

internal fun MlnFfiStyleBinding.setFeatureState(
  sourceId: String,
  featureId: String,
  state: JsonObject,
  sourceLayerId: String? = null,
) {
  mutateFeatureState { session ->
    session.setFeatureState(
      featureStateSelector(sourceId, sourceLayerId, featureId),
      state.toJsonBytes(),
    )
  }
}

internal fun MlnFfiStyleBinding.getFeatureState(
  sourceId: String,
  featureId: String,
  sourceLayerId: String? = null,
): JsonObject =
  withRenderSession { session ->
    val bytes = session.getFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId))
    if (bytes.isEmpty()) JsonObject(emptyMap())
    else bytes.toJsonElement() as? JsonObject ?: JsonObject(emptyMap())
  } ?: JsonObject(emptyMap())

internal fun MlnFfiStyleBinding.removeFeatureState(
  sourceId: String,
  featureId: String,
  stateKey: String? = null,
  sourceLayerId: String? = null,
) {
  mutateFeatureState { session ->
    session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId, stateKey))
  }
}

internal fun MlnFfiStyleBinding.resetFeatureStates(
  sourceId: String,
  sourceLayerId: String? = null,
) {
  mutateFeatureState { session ->
    session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId))
  }
}

/** Feature-state writes live on the render session; the map still has to be asked to draw. */
private fun MlnFfiStyleBinding.mutateFeatureState(action: (RenderSessionHandle) -> Unit) {
  withRenderSession(action) ?: return
  mutateMap {}
}

private fun featureStateSelector(
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
