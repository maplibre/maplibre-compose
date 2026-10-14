package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.RasterTileSource
import org.maplibre.compose.style.internal.StyleValue
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

class StyleReconcilerTest {

  @Test
  fun duplicate_resource_ids_fail_a_complete_revision() {
    val source = source("duplicate").definition()

    val error =
      assertFailsWith<IllegalArgumentException> {
        StyleSnapshot(
          sources = listOf(source, source),
          layers = emptyList(),
          images = emptyList(),
        )
      }

    assertTrue(error.message.orEmpty().contains("Source ID 'duplicate'"))
  }

  @Test
  fun reconciliation_applies_sources_before_layers_and_retains_unchanged_resources() {
    val style = RecordingStyleBinding()
    val recording = RecordingOperations(style)
    val reconciler = StyleReconciler()
    val source = source("tiles")
    val layer = TestLayer("raster", "raster", source)
    val revision = revision(source, layer)

    reconciler.apply(recording, revision)
    assertEquals(listOf("source:tiles", "layer:raster"), recording.additions)

    recording.additions.clear()
    reconciler.apply(recording, revision)
    assertTrue(recording.additions.isEmpty())
    assertEquals(listOf("raster"), style.layerIds())
  }

  @Test
  fun unchanged_layers_skip_preparation_but_duration_scale_changes_still_apply() {
    val delegate = RecordingStyleBinding()
    var propertyChecks = 0
    val style =
      object : StyleBinding by delegate {
        override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? {
          propertyChecks++
          return null
        }
      }
    val source = source("tiles")
    val layer =
      TestLayer("raster", "raster", source).apply {
        filterUnsupportedProperties = true
        paint("raster-opacity", JsonPrimitive(0.5))
        paintTransition("raster-opacity", TransitionOptions(200.milliseconds))
      }
    val reconciler = StyleReconciler()
    reconciler.apply(style, revision(source, layer))
    val initialChecks = propertyChecks
    reconciler.apply(style, revision(source, layer))
    assertEquals(initialChecks, propertyChecks)
    assertTrue(delegate.layerPropertyWrites.isEmpty())

    reconciler.apply(style, revision(source, layer).copy(animatorDurationScale = 0f))
    assertTrue(propertyChecks > initialChecks)
    assertEquals(
      0f,
      delegate.layers
        .getValue("raster")["paint"]!!
        .jsonObject
        .getValue("raster-opacity-transition")
        .jsonObject
        .getValue("duration")
        .jsonPrimitive
        .float,
    )
  }

  @Test
  fun construction_changes_replace_layers_but_live_properties_do_not() {
    val delegate = RecordingStyleBinding()
    val recording = RecordingOperations(delegate)
    val reconciler = StyleReconciler()
    val source = source("tiles")
    val layer = TestLayer("raster", "raster", source)
    val first = revision(source, layer)
    reconciler.apply(recording, first)
    recording.additions.clear()
    layer.minZoom = 3f
    layer.paint("raster-opacity", JsonPrimitive(0.5))
    val second = revision(source, layer)
    reconciler.apply(recording, second)
    assertTrue(recording.additions.isEmpty())

    val definition = second.layers.single().definition
    val withConstruction =
      second.copy(
        layers =
          listOf(
            second.layers
              .single()
              .copy(
                definition =
                  definition.copy(
                    properties = definition.properties + ("metadata" to StyleValue.Json(JsonNull))
                  )
              )
          )
      )
    reconciler.apply(recording, withConstruction)
    assertEquals(listOf("layer:raster"), recording.additions)
    recording.additions.clear()
    reconciler.apply(recording, second)
    assertEquals(listOf("layer:raster"), recording.additions)
  }

  @Test
  fun a_later_complete_revision_removes_resources_left_by_a_partial_failure() {
    var fail = true
    val delegate = RecordingStyleBinding()
    val style =
      object : StyleBinding by delegate {
        override fun addSource(definition: SourceDefinition): Boolean {
          if (fail && definition.id == "refused") error("engine refused")
          return delegate.addSource(definition)
        }
      }
    val reconciler = StyleReconciler()
    val first = source("first")

    assertFailsWith<IllegalStateException> {
      reconciler.apply(
        style,
        revision(first, TestLayer("first-layer", "raster", first))
          .copy(sources = listOf(first.definition(), source("refused").definition())),
      )
    }
    assertEquals(setOf("first"), delegate.installedSourceIds)
    assertTrue(delegate.installedLayerIds.isEmpty())

    fail = false
    val second = source("second")
    reconciler.apply(style, revision(second, TestLayer("second-layer", "raster", second)))

    assertEquals(setOf("second"), delegate.installedSourceIds)
    assertEquals(setOf("second-layer"), delegate.installedLayerIds)
  }

  @Test
  fun a_changed_image_is_replaced_in_place_and_a_dropped_image_is_removed() {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    fun revisionWith(vararg images: StyleImageDefinition) =
      StyleSnapshot(emptyList(), emptyList(), images.toList())
    val icon =
      StyleImageDefinition("icon", PreparedImage.fromBitmap(FakeImageBitmap(1, 1)), false, null)

    reconciler.apply(style, revisionWith(icon))
    assertEquals(setOf("icon"), style.imageIds)
    assertTrue(style.replacedImages.isEmpty())

    reconciler.apply(style, revisionWith(icon.copy(sdf = true)))
    assertEquals(listOf("icon"), style.replacedImages)
    assertEquals(setOf("icon"), style.imageIds)

    reconciler.apply(style, revisionWith())
    assertTrue(style.imageIds.isEmpty())
  }

  @Test
  fun a_failed_replacement_is_replaced_again_by_the_next_revision() {
    val refused = mutableSetOf("icon")
    val style = RecordingStyleBinding(refusedImageReplacements = refused)
    val reconciler = StyleReconciler()
    fun revisionWith(vararg images: StyleImageDefinition) =
      StyleSnapshot(emptyList(), emptyList(), images.toList())
    val icon =
      StyleImageDefinition("icon", PreparedImage.fromBitmap(FakeImageBitmap(1, 1)), false, null)

    reconciler.apply(style, revisionWith(icon))
    assertFailsWith<StyleMutationException> {
      reconciler.apply(style, revisionWith(icon.copy(sdf = true)))
    }
    refused.clear()

    // The engine may hold either image, so reverting is not a no-op and not a plain add.
    reconciler.apply(style, revisionWith(icon))
    assertEquals(listOf("icon"), style.replacedImages)
    assertEquals(setOf("icon"), style.imageIds)
  }

  @Test
  fun a_failed_replacement_is_removed_when_the_next_revision_drops_it() {
    val style = RecordingStyleBinding(refusedImageReplacements = setOf("icon"))
    val reconciler = StyleReconciler()
    fun revisionWith(vararg images: StyleImageDefinition) =
      StyleSnapshot(emptyList(), emptyList(), images.toList())
    val icon =
      StyleImageDefinition("icon", PreparedImage.fromBitmap(FakeImageBitmap(1, 1)), false, null)

    reconciler.apply(style, revisionWith(icon))
    assertFailsWith<StyleMutationException> {
      reconciler.apply(style, revisionWith(icon.copy(sdf = true)))
    }

    // The engine still holds the previous image, so dropping the ID removes it.
    reconciler.apply(style, revisionWith())
    assertTrue(style.imageIds.isEmpty())
  }

  @Test
  fun an_image_source_uploads_only_changed_pixels_after_its_corners() {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    fun revisionWith(position: PositionQuad, image: PreparedImage) =
      StyleSnapshot(
        sources = listOf(ImageSource("image", position, image).definition()),
        layers = emptyList(),
        images = emptyList(),
      )
    val first = image(OpaqueRed)

    reconciler.apply(style, revisionWith(Quad, first))
    assertSame(first, style.addedImageSourceImages["image"], "the add takes the prepared image")

    reconciler.apply(style, revisionWith(Quad, first))
    assertTrue(style.imageSourceWrites.isEmpty(), "the same prepared image writes nothing")

    val next = image(OpaqueGreen)
    val moved = Quad.copy(topLeft = Position(-2.0, 1.0))
    reconciler.apply(style, revisionWith(moved, next))
    assertEquals(
      listOf(
        listOf(moved.topLeft, moved.topRight, moved.bottomRight, moved.bottomLeft),
        next,
      ),
      style.imageSourceWrites.map { it.second },
    )
    assertSame(next, style.imageSourceWrites.last().second)
  }

  @Test
  fun preparing_resolves_anchors_without_installing_or_replaying_application_callbacks() {
    val base = TestLayer("base", "background")
    val style = RecordingStyleBinding(layers = listOf(base))
    val reconciler = StyleReconciler()
    val source = source("tiles")
    val layer = TestLayer("raster", "raster", source)
    var predicates = 0
    val desired =
      revision(source, layer).let {
        it.copy(
          layers =
            listOf(
              it.layers
                .single()
                .copy(
                  anchor =
                    Anchor.Below {
                      predicates++
                      it.id == "base"
                    }
                )
            )
        )
      }
    val prepared = reconciler.prepare(style, desired)
    assertEquals(1, predicates)
    assertTrue(style.installedLayerIds.isEmpty())
    reconciler.apply(style, prepared)
    assertEquals(1, predicates)
    assertEquals(listOf("raster", "base"), style.layerIds())

    val replacement = RecordingStyleBinding(layers = listOf(base))
    assertFailsWith<IllegalStateException> { reconciler.apply(replacement, prepared) }
    style.invalidate()
    assertFailsWith<IllegalStateException> { reconciler.apply(style, prepared) }
    assertTrue(replacement.installedLayerIds.isEmpty())
  }

  private fun image(pixel: Int) = PreparedImage.fromBitmap(FakeImageBitmap(1, 1, intArrayOf(pixel)))

  private fun source(id: String) =
    RasterTileSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"))

  private fun revision(source: RasterTileSource, layer: TestLayer) =
    StyleSnapshot(
      sources = listOf(source.definition()),
      layers = listOf(StyleSnapshot.Layer(layer.definition(), Anchor.Top, null, null)),
      images = emptyList(),
    )

  private companion object {
    const val OpaqueRed = 0xffff0000.toInt()
    const val OpaqueGreen = 0xff00ff00.toInt()

    val Quad =
      PositionQuad(
        Position(-1.0, 1.0),
        Position(1.0, 1.0),
        Position(1.0, -1.0),
        Position(-1.0, -1.0),
      )
  }

  private class RecordingOperations(private val delegate: RecordingStyleBinding) :
    StyleBinding by delegate {
    val additions = mutableListOf<String>()

    override fun addSource(definition: SourceDefinition): Boolean =
      delegate.addSource(definition).also { additions += "source:${definition.id}" }

    override fun addLayer(layer: StyleValue, beforeLayerId: String): Boolean =
      delegate.addLayer(layer, beforeLayerId).also {
        additions += "layer:${((layer.json as JsonObject)["id"] as JsonPrimitive).content}"
      }
  }
}
