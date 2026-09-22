package org.maplibre.compose.layers

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.style.TransitionOptions

class LayerPropertiesTest {
  @Test
  fun snapshots_detach_json_and_seal_the_builder() {
    val cache = testLayerPropertyCache()
    val nested = mutableMapOf<String, JsonElement>("mode" to JsonPrimitive("first"))
    lateinit var builder: LayerProperties
    fun snapshot() = cache.snapshot {
      builder = this
      root("options", JsonObject(nested))
    }
    val first = snapshot()
    nested["mode"] = JsonPrimitive("changed")
    assertFailsWith<IllegalStateException> { builder.root("options", JsonNull) }
    val second = snapshot()
    assertEquals(JsonPrimitive("first"), first.definition.value["options"]!!.jsonObject["mode"])
    assertEquals(JsonPrimitive("changed"), second.definition.value["options"]!!.jsonObject["mode"])
  }

  @Test
  fun each_invocation_is_complete_and_last_write_wins() {
    val cache = testLayerPropertyCache()
    val first = cache.snapshot {
      root("custom", JsonNull)
      layout("literal", JsonNull)
      paint("color", const("red"))
      paintTransition("color", TransitionOptions(200.milliseconds))
      paintTransition("color", null)
      paint("removed", const(1))
      paint("removed", null)
    }
    val json = first.definition.value
    assertEquals(JsonNull, json["custom"])
    assertEquals(JsonNull, json["layout"]!!.jsonObject["literal"])
    assertEquals(JsonObject(mapOf("color" to JsonPrimitive("red"))), json["paint"]!!.jsonObject)
    val empty = cache.snapshot {}
    assertFalse(empty.definition.value.containsKey("paint"))
    assertFalse(empty.definition.value.containsKey("layout"))
    assertFalse(empty.definition.value.containsKey("custom"))
    // An intervening uncommitted snapshot must not become the next invocation's declaration.
    cache.snapshot { paint("color", const("discarded")) }
    assertEquals(
      json,
      cache
        .snapshot {
          root("custom", JsonNull)
          layout("literal", JsonNull)
          paint("color", const("red"))
        }
        .definition
        .value,
    )
    assertEquals(JsonPrimitive("red"), json["paint"]!!.jsonObject["color"])
  }

  @Test
  fun compilation_cache_preserves_images_and_invalidates_unit_contexts() {
    val cache = testLayerPropertyCache()
    val bitmap = image(FakeImageBitmap(2, 2))
    fun snapshot(scale: Float) = cache.snapshot {
      layout("icon", bitmap)
      paint("size", const(2.em), LayerExpressionContext(emScale = const(scale)))
    }
    val first = snapshot(3f)
    val second = snapshot(4f)
    assertSame(first.images.values.single(), second.images.values.single())
    assertEquals(JsonPrimitive(6f), first.definition.value["paint"]!!.jsonObject["size"])
    assertEquals(JsonPrimitive(8f), second.definition.value["paint"]!!.jsonObject["size"])
    val literal = cache.snapshot {
      layout("icon", bitmap)
      layout("icon", JsonPrimitive("sprite"))
    }
    assertTrue(literal.images.isEmpty())
    assertEquals(JsonPrimitive("sprite"), literal.definition.value["layout"]!!.jsonObject["icon"])
    val image = cache.snapshot {
      layout("icon", JsonPrimitive("sprite"))
      layout("icon", bitmap)
    }
    assertEquals(1, image.images.size)
    assertFalse(image.definition.value.containsKey("layout"))
    assertTrue(cache.snapshot {}.images.isEmpty())
  }

  @Test
  fun identity_and_sections_cannot_be_overridden_as_root_properties() {
    val cache = testLayerPropertyCache()
    listOf("id", "type", "paint", "layout").forEach { reserved ->
      assertFailsWith<IllegalArgumentException> {
        cache.snapshot { root(reserved, JsonPrimitive("wrong")) }
      }
    }
    assertFailsWith<IllegalArgumentException> { cache.snapshot { paintTransition("", null) } }
    assertFailsWith<IllegalArgumentException> { cache.snapshot { root("source", JsonNull) } }
  }
}

internal fun testLayerPropertyCache(): LayerPropertyCache =
  LayerPropertyCache(
    LayerPropertyCompiler(
      Density(1f),
      LayoutDirection.Ltr,
      const(1f),
      {
        object : GraphicsContext {
          override fun createGraphicsLayer(): GraphicsLayer =
            error("Images must not render while declaring properties")

          override fun releaseGraphicsLayer(layer: GraphicsLayer) = Unit
        }
      },
    )
  )

internal fun LayerPropertyCache.snapshot(
  properties: LayerProperties.() -> Unit
): LayerPropertySnapshot {
  begin()
  val builder = LayerProperties(this)
  return try {
    builder.properties()
    builder.finish("layer", "plugin", null, false)
  } finally {
    builder.close()
    end()
  }
}
