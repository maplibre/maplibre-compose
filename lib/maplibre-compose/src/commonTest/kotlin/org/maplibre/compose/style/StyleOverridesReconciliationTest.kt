package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.style.internal.StyleOverrideDefinition

class StyleOverridesReconciliationTest {
  @Test
  fun overrides_update_restore_and_follow_the_current_base_generation() {
    val first = RecordingStyleBinding(baseLight = json("""{"anchor":"map"}"""))
    val second = RecordingStyleBinding(baseLight = json("""{"intensity":0.8}"""))
    val light = json("""{"intensity":0.25}""")
    val reconciler = StyleReconciler()
    val overridden = revision(StyleOverrideDefinition(light = light))

    reconciler.apply(first, StyleSnapshot.Empty)
    assertEquals(0, first.lightWrites.size, "Unowned roots must not be written")
    reconciler.apply(first, overridden)
    reconciler.apply(first, overridden)
    assertEquals(1, first.lightWrites.size, "Equal overrides must not restart transitions")
    assertEquals(light, JsonObject(first.lightProperties))
    reconciler.apply(first, StyleSnapshot.Empty)
    assertEquals(first.baseLight, JsonObject(first.lightProperties))

    reconciler.apply(second, overridden)
    assertEquals(light, JsonObject(second.lightProperties))
    reconciler.apply(second, StyleSnapshot.Empty)
    assertEquals(second.baseLight, JsonObject(second.lightProperties))
  }

  @Test
  fun transitions_follow_duration_scale_but_restoration_preserves_base_timing() {
    val base = json("""{"color-transition":{"duration":700}}""")
    val style = RecordingStyleBinding(baseLight = base)
    val light = json("""{"color-transition":{"duration":1000,"delay":200}}""")
    val reconciler = StyleReconciler()
    val overridden = revision(StyleOverrideDefinition(light = light))
    reconciler.apply(style, overridden.copy(animatorDurationScale = 0.5f))
    assertTransition(style, duration = 500.0, delay = 100.0)
    reconciler.apply(style, overridden.copy(animatorDurationScale = 0f))
    assertTransition(style, duration = 0.0, delay = 0.0)
    reconciler.apply(style, StyleSnapshot.Empty.copy(animatorDurationScale = 0f))
    assertEquals(base, JsonObject(style.lightProperties))
  }

  @Test
  fun optional_root_removal_is_distinct_from_inheritance() {
    val sky = json("""{"atmosphere-blend":0.5}""")
    val projection = json("""{"type":"globe"}""")
    val terrain = json("""{"source":"base-dem","exaggeration":2}""")
    val style =
      RecordingStyleBinding(
        baseSky = sky,
        baseProjection = projection,
        baseTerrain = terrain,
      )
    val reconciler = StyleReconciler()
    reconciler.apply(
      style,
      revision(
        StyleOverrideDefinition(
          sky = JsonNull,
          projection = json("""{"type":"mercator"}"""),
          terrain = JsonNull,
        )
      ),
    )
    assertNull(style.sky)
    assertNull(style.terrain)
    assertEquals(JsonPrimitive("mercator"), style.projection["type"])
    reconciler.apply(style, StyleSnapshot.Empty)
    assertEquals(sky, style.sky)
    assertEquals(projection, style.projection)
    assertEquals(terrain, style.terrain)
  }

  @Test
  fun terrain_detaches_before_its_source_is_replaced_or_removed() {
    val recording = RecordingStyleBinding()
    val style =
      object : StyleBinding by recording {
        override fun removeSource(sourceId: String) {
          assertNull(recording.terrain, "Terrain must release its source before removal")
          recording.removeSource(sourceId)
        }

        override fun setTerrain(terrain: JsonObject?) {
          terrain?.get("source")?.let {
            assertEquals(true, recording.sourceExists((it as JsonPrimitive).content))
          }
          recording.setTerrain(terrain)
        }
      }
    val first = SourceDefinition.Json("dem", json("""{"type":"raster-dem","tiles":[]}"""))
    val second =
      SourceDefinition.Json("dem", json("""{"type":"raster-dem","tiles":[],"tileSize":256}"""))
    val terrain = json("""{"source":"dem","exaggeration":1}""")
    val reconciler = StyleReconciler()
    val overridden =
      revision(StyleOverrideDefinition(terrain = terrain)).copy(sources = listOf(first))
    reconciler.apply(style, overridden)
    reconciler.apply(style, overridden.copy(sources = listOf(second)))
    assertEquals(listOf(terrain, null, terrain), recording.terrainWrites)
    reconciler.apply(style, StyleSnapshot.Empty)
    assertNull(recording.terrain)
    assertEquals(false, recording.sourceExists("dem"))
  }

  @Test
  fun a_rejected_root_does_not_block_other_roots_and_is_retried() {
    val recording = RecordingStyleBinding()
    var attempts = 0
    val style =
      object : StyleBinding by recording {
        override fun setLight(light: JsonObject) {
          attempts++
          if (attempts == 1) throw StyleMutationException("refused", null)
          recording.setLight(light)
        }
      }
    val light = json("""{"intensity":0.25}""")
    val sky = json("""{"atmosphere-blend":0.5}""")
    val reconciler = StyleReconciler()
    val overridden = revision(StyleOverrideDefinition(light = light, sky = sky))
    reconciler.apply(style, overridden)
    assertEquals(sky, recording.sky)
    assertEquals(emptyMap(), recording.lightProperties)
    reconciler.apply(style, overridden)
    assertEquals(light, JsonObject(recording.lightProperties))
    assertEquals(2, attempts)
  }

  private fun assertTransition(style: RecordingStyleBinding, duration: Double, delay: Double) {
    val transition = style.lightProperties.getValue("color-transition").jsonObject
    assertEquals(duration, transition.getValue("duration").jsonPrimitive.double)
    assertEquals(delay, transition.getValue("delay").jsonPrimitive.double)
  }

  private fun revision(overrides: StyleOverrideDefinition): StyleSnapshot =
    StyleSnapshot.Empty.copy(overrides = overrides)

  private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
