package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The retained feature-state copy accepts writes while no renderer exists. Surface-loss tests
 * exercise restore on a live map; this class asserts the store without a GPU.
 */
class MlnFfiFeatureStateStoreTest {

  @Test
  fun set_merges_keys_on_the_same_feature() {
    val store = MlnFfiFeatureStateStore()
    store.set("points", null, "1", state("before-surface"))
    store.set("points", null, "1", state("without-surface"))
    assertEquals(state("before-surface", "without-surface"), store.get("points", null, "1"))
  }

  @Test
  fun reset_clears_one_source_and_leaves_another() {
    val store = MlnFfiFeatureStateStore()
    store.set("points", null, "1", state("keep-me"))
    store.set("other", null, "1", state("leave-me"))
    store.reset("points", null)
    assertEquals(JsonObject(emptyMap()), store.get("points", null, "1"))
    assertEquals(state("leave-me"), store.get("other", null, "1"))
  }

  @Test
  fun remove_one_key_keeps_the_rest() {
    val store = MlnFfiFeatureStateStore()
    store.set("points", null, "1", state("before-surface", "without-surface"))
    store.remove("points", null, "1", "before-surface")
    assertEquals(state("without-surface"), store.get("points", null, "1"))
  }

  private fun state(vararg keys: String): JsonObject = buildJsonObject {
    keys.forEach { key -> put(key, true) }
  }
}
