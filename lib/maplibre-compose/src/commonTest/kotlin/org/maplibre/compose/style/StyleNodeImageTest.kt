package org.maplibre.compose.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source

@OptIn(ExperimentalCoroutinesApi::class)
class StyleNodeImageTest {
  @Test
  fun pending_painter_keeps_the_old_image_without_blocking_other_changes() = runTest {
    val first = painter(Color.Red)
    val replacement = painter(Color.Blue)
    val ready = CompletableDeferred<Unit>()
    val snapshots = mutableListOf<StyleSnapshot>()
    val root =
      StyleNode(
        RecordingStyleBinding(),
        backgroundScope,
        preparePainter = { request ->
          if (request == replacement) ready.await()
          content(if (request == replacement) 2 else 1)
        },
        publish = { snapshots += it },
      )
    val source = GeoJsonSource("points", data(1), GeoJsonOptions())
    val retained = imageLayer("retained", first, source)
    val removed = imageLayer("removed", first, source)
    root.children += listOf(retained, removed)
    root.commit()
    runCurrent()
    val original = snapshots.last().images.single()

    val updatedSource = GeoJsonSource("points", data(2), GeoJsonOptions())
    retained.source = updatedSource
    retained.definition =
      TestLayer("retained", "fill", updatedSource)
        .apply {
          paint("fill-opacity", const(0.5f).asLayerProperty())
        }
        .definition()
    retained.imageProperties = imageProperty(replacement)
    root.children.remove(removed)
    root.commit()
    runCurrent()
    val pending = snapshots.last()
    assertTrue(pending.imagesPending)
    assertEquals(data(2), (pending.sources.single() as SourceDefinition.GeoJson).data)
    assertEquals(listOf("retained"), pending.layers.map { it.definition.id })
    assertEquals(JsonPrimitive(0.5f), paint(pending)["fill-opacity"])
    assertEquals(listOf(original), pending.images)
    assertEquals(JsonPrimitive(original.id), paint(pending)["fill-pattern"])

    ready.complete(Unit)
    runCurrent()
    val completed = snapshots.last()
    assertFalse(completed.imagesPending)
    assertEquals(2, completed.images.single().image.width)
    assertEquals(JsonPrimitive(completed.images.single().id), paint(completed)["fill-pattern"])
    root.close()
  }

  @Test
  fun inline_painter_completion_cannot_publish_a_partially_ready_snapshot() = runTest {
    val revisions = mutableListOf<StyleSnapshot>()
    val root =
      StyleNode(
        RecordingStyleBinding(),
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        preparePainter = { content(1) },
        publish = { revisions += it },
      )
    try {
      root.children +=
        listOf(imageLayer("red", painter(Color.Red)), imageLayer("blue", painter(Color.Blue)))
      root.commit()
      val ready = revisions.filterNot { it.imagesPending }.single()
      val imageIds = ready.images.map { JsonPrimitive(it.id) }
      assertEquals(2, ready.layers.size)
      assertTrue(
        ready.layers.all {
          (it.definition.value["paint"] as JsonObject)["fill-pattern"] in imageIds
        }
      )
    } finally {
      root.close()
    }
  }

  @Test
  fun removed_images_and_closed_compositions_cancel_their_preparation() = runTest {
    var started = 0
    var cancelled = 0
    val root =
      StyleNode(
        RecordingStyleBinding(),
        backgroundScope,
        preparePainter = {
          started++
          try {
            awaitCancellation()
          } finally {
            cancelled++
          }
        },
      )
    val request = painter(Color.Red)
    val layer = imageLayer("pending", request)
    root.children += layer
    root.commit()
    runCurrent()
    assertEquals(1, started)
    root.children.clear()
    root.commit()
    runCurrent()
    assertEquals(1, cancelled)
    root.children += layer
    root.commit()
    runCurrent()
    assertEquals(2, started)
    root.close()
    runCurrent()
    assertEquals(2, cancelled)
  }

  private fun imageLayer(id: String, request: StyleImageRequest, source: Source? = null) =
    LayerNode(TestLayer(id, "fill", source).definition(), Anchor.Top).apply {
      this.source = source
      imageProperties = imageProperty(request)
    }

  private fun imageProperty(request: StyleImageRequest) =
    mapOf(
      StyleProperty("paint", "fill-pattern") to
        LayerProperty<ImageValue>(setOf(request)) { JsonPrimitive(it.getValue(request)) }
    )

  private fun data(value: Int) =
    GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[],"value":$value}""")

  private fun paint(revision: StyleSnapshot) =
    revision.layers.first().definition.value["paint"] as JsonObject

  private fun content(width: Int) =
    ResolvedStyleImage(ImageSnapshot.capture(FakeImageBitmap(width, 1)), false, null)

  private fun painter(color: Color) =
    StyleImageRequest.Painter(
      PainterLiteral.of(ColorPainter(color), DpSize(2.dp, 2.dp), false, null),
      graphics,
      Density(1f),
      LayoutDirection.Ltr,
    )

  private val graphics =
    object : GraphicsContext {
      override fun createGraphicsLayer(): GraphicsLayer =
        error("Preparation is controlled by the test")

      override fun releaseGraphicsLayer(layer: GraphicsLayer) = Unit
    }
}
