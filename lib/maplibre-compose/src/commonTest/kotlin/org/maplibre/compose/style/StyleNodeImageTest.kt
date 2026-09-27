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
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
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
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource

@OptIn(ExperimentalCoroutinesApi::class)
class StyleNodeImageTest {
  @Test
  fun shared_images_and_pending_replacements_follow_committed_properties() = runTest {
    val first = painter(Color.Red)
    val equalPixels = painter(Color.Green)
    val replacement = painter(Color.Blue)
    val initialGate = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val starts = mutableListOf<StyleImageRequest>()
    val revisions = mutableListOf<StyleSnapshot>()
    val root =
      StyleNode(
        RecordingStyleBinding(),
        backgroundScope,
        preparePainter = { request ->
          starts += request
          if (request == replacement) gate.await() else initialGate.await()
          content(if (request == replacement) 2 else 1)
        },
        publish = { revisions += it },
      )
    runCurrent()
    assertTrue(starts.isEmpty())
    val sprite =
      TestLayer("layer-0", "background").apply {
        paint(
          "background-pattern",
          image("sprite").compile(ExpressionContext.None).asLayerProperty(),
        )
      }
    val spriteLayer = LayerNode(sprite.definition(), Anchor.Top)
    root.children += spriteLayer
    root.commit()
    runCurrent()
    assertSame(spriteLayer.definition, revisions.last().layers.single().definition)
    val spriteValue = paint(revisions.last())["background-pattern"]
    update(root, listOf(first, first, equalPixels))
    runCurrent()
    assertEquals(
      spriteValue,
      paint(revisions.last())["background-pattern"],
      "a sprite also stays visible while its replacement is prepared",
    )
    assertEquals(2, starts.size, "one preparation per shared request")
    initialGate.complete(Unit)
    runCurrent()
    assertEquals(1, revisions.last().images.size, "equal pixels share an image")
    val originalSource = revisions.last().sources.single()
    val original = revisions.last().images.single()

    update(root, listOf(replacement), opacity = 0.5f)
    runCurrent()
    val pending = revisions.last()
    assertTrue(pending.imagesPending)
    assertNotEquals(
      originalSource,
      pending.sources.single(),
      "source updates apply while painters wait",
    )
    assertEquals(listOf(original), pending.images)
    assertEquals(JsonPrimitive(original.id), paint(pending)["background-pattern"])
    assertEquals(JsonPrimitive(0.5f), paint(pending)["background-opacity"])
    assertEquals(1, pending.layers.size, "unrelated layer removals apply immediately")

    gate.complete(Unit)
    runCurrent()
    val ready = revisions.last()
    assertFalse(ready.imagesPending)
    assertEquals(2, ready.images.single().image.width)
    assertEquals(JsonPrimitive(ready.images.single().id), paint(ready)["background-pattern"])
    update(root, emptyList())
    runCurrent()
    assertTrue(revisions.last().images.isEmpty())
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
      update(root, listOf(painter(Color.Red), painter(Color.Blue)))
      val ready = revisions.filterNot { it.imagesPending }.single()
      val imageIds = ready.images.map { JsonPrimitive(it.id) }
      assertEquals(2, ready.layers.size)
      assertTrue(
        ready.layers.all {
          (it.definition.value["paint"] as JsonObject)["background-pattern"] in imageIds
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
    update(root, listOf(request))
    runCurrent()
    assertEquals(1, started)
    update(root, emptyList())
    runCurrent()
    assertEquals(1, cancelled)
    update(root, listOf(request))
    runCurrent()
    assertEquals(2, started)
    root.close()
    runCurrent()
    assertEquals(2, cancelled)
  }

  private fun update(root: StyleNode, requests: List<StyleImageRequest>, opacity: Float = 1f) {
    val source =
      GeoJsonSource(
        "points",
        GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[],"value":$opacity}"""),
        GeoJsonOptions(),
      )
    val previous = root.children.filterIsInstance<LayerNode>().associateBy { it.definition.id }
    val layers = requests.mapIndexed { index, request ->
      val id = "layer-$index"
      val definition =
        TestLayer(id, "background")
          .apply {
            paint("background-opacity", const(opacity).asLayerProperty())
          }
          .definition()
      (previous[id] ?: LayerNode(definition, Anchor.Top)).apply {
        this.definition = definition
        this.source = source
        imageProperties =
          mapOf(
            StyleProperty("paint", "background-pattern") to
              LayerProperty<ImageValue>(setOf(request)) { JsonPrimitive(it.getValue(request)) }
          )
      }
    }
    root.children.clear()
    root.children.addAll(layers)
    root.commit()
  }

  private fun paint(revision: StyleSnapshot) =
    revision.layers.first().definition.value["paint"] as JsonObject

  private fun content(width: Int) =
    StyleImageContent(ImageSnapshot.capture(FakeImageBitmap(width, 1)), false, null)

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
