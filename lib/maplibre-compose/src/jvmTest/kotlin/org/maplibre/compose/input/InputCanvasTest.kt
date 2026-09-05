package org.maplibre.compose.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A non-map consumer of the production recognizers, with its own transform policy and edit model.
 */
@OptIn(ExperimentalTestApi::class)
class InputCanvasTest {
  @Test
  fun canvas_transforms_use_shared_recognition_without_map_thresholds_or_camera_objects() =
    runComposeUiTest {
      val model = CanvasModel()
      setContent {
        Canvas(Modifier.size(300.dp).testTag("canvas").canvasInput(model)) { model.draw(this) }
      }
      onNodeWithTag("canvas").performTouchInput {
        // A 20px pair is valid for this canvas; the map policy has a larger minimum span.
        down(0, Offset(100f, 100f))
        down(1, Offset(120f, 100f))
        moveTo(0, Offset(90f, 110f))
        moveTo(1, Offset(130f, 110f))
        up(0)
        up(1)
      }
      runOnIdle {
        assertTrue(model.zoom > 1.5f)
        assertTrue(model.offset.y > 0f)
      }
      onNodeWithTag("canvas").performTouchInput {
        down(0, Offset(100f, 100f))
        down(1, Offset(140f, 100f))
        moveTo(0, Offset(120f, 80f))
        moveTo(1, Offset(120f, 120f))
        up(0)
        up(1)
      }
      runOnIdle { assertTrue(abs(model.rotation) > 45f) }
    }

  @Test
  fun a_selection_reserves_drag_while_background_drag_moves_the_canvas() = runComposeUiTest {
    val model = CanvasModel()
    setContent {
      Canvas(Modifier.size(300.dp).testTag("canvas").canvasInput(model)) { model.draw(this) }
    }
    onNodeWithTag("canvas").performTouchInput {
      down(Offset(40f, 40f))
      moveBy(Offset(40f, 0f))
      up()
    }
    runOnIdle {
      assertEquals(Offset.Zero, model.offset)
      assertEquals(Offset(75f, 40f), model.selection)
      assertEquals(1, model.commits)
    }
    val pixels = onNodeWithTag("canvas").captureToImage().toPixelMap()
    assertEquals(Color.Red, pixels[75, 40])
    onNodeWithTag("canvas").performTouchInput {
      down(Offset(180f, 180f))
      moveBy(Offset(30f, 0f))
      up()
    }
    runOnIdle {
      assertEquals(Offset(25f, 0f), model.offset)
      assertEquals(Offset(75f, 40f), model.selection)
    }
  }

  @Test
  fun adding_a_contact_rolls_back_an_edit_and_does_not_turn_it_into_a_transform() =
    runComposeUiTest {
      val model = CanvasModel()
      setContent {
        Canvas(Modifier.size(300.dp).testTag("canvas").canvasInput(model)) { model.draw(this) }
      }
      onNodeWithTag("canvas").performTouchInput {
        down(0, Offset(40f, 40f))
        moveBy(Offset(40f, 0f))
        down(1, Offset(160f, 40f))
        moveBy(1, Offset(50f, 20f))
        up(1)
        up(0)
      }
      runOnIdle {
        assertEquals(Offset(40f, 40f), model.preview)
        assertEquals(0, model.commits)
        assertEquals(Offset.Zero, model.offset)
        assertEquals(1f, model.zoom)
      }
    }
}

private class CanvasModel {
  var offset by mutableStateOf(Offset.Zero)
  var zoom by mutableStateOf(1f)
  var rotation by mutableStateOf(0f)
  var selection = Offset(40f, 40f)
  var preview by mutableStateOf(selection)
  var commits = 0

  fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) =
    with(scope) {
      drawRect(Color.White)
      translate(offset.x, offset.y) {
        scale(zoom) {
          rotate(rotation) { drawCircle(Color.Blue, radius = 30f, center = Offset(150f, 150f)) }
        }
      }
      drawCircle(Color.Red, radius = 12f, center = preview)
    }
}

private fun Modifier.canvasInput(model: CanvasModel): Modifier =
  pointerInput(model) {
    var drag: PointerDrag? = null
    var pair: PointerTransform? = null
    var editing = false
    fun cancel() {
      drag?.finish()
      drag = null
      pair?.cancel()
      pair = null
      if (editing) model.preview = model.selection
      editing = false
    }
    val consumption = PointerInputConsumption(::cancel)
    fun transform(first: PointerInputChange, second: PointerInputChange) =
      PointerTransform(
        first,
        second,
        CanvasTransformPolicy,
        onStart = { _, _ -> true },
        onDelta = { component, delta ->
          when (component) {
            TransformComponent.Pan -> model.offset += delta.pan
            TransformComponent.Scale -> model.zoom *= delta.scale.toFloat()
            TransformComponent.Rotation -> model.rotation += delta.rotation.toFloat()
            TransformComponent.VerticalDrag -> error("canvas has no vertical-only transform")
          }
          true
        },
        onEnd = { _, _ -> true },
        onCancel = {},
      )
    try {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Main)
          consumption.main(event) { input ->
            val down = input.changes.filter { it.pressed }
            when {
              down.size >= 2 && editing -> consumption.suppress()
              down.size >= 2 -> {
                drag?.finish()
                drag = null
                val recognizer = pair ?: transform(down[0], down[1]).also { pair = it }
                if (recognizer.move(down[0], down[1])) down.forEach { it.consume() }
              }
              down.size == 1 && pair == null -> {
                val change = down.single()
                if (!change.previousPressed) {
                  editing = (change.position - model.selection).getDistance() <= 16f
                  drag = PointerDrag(change, slop = 5f)
                } else {
                  drag?.move(change)?.let { motion ->
                    if (editing) model.preview += motion.delta else model.offset += motion.delta
                    change.consume()
                  }
                }
              }
              down.isEmpty() -> {
                if (drag?.finish() == true && editing) {
                  model.selection = model.preview
                  model.commits++
                }
                pair?.end()
                drag = null
                pair = null
                editing = false
              }
            }
          }
          consumption.final(awaitPointerEvent(PointerEventPass.Final))
        }
      }
    } finally {
      cancel()
    }
  }

private object CanvasTransformPolicy : PointerTransformPolicy {
  override fun recognize(motion: PairMotion, active: Set<TransformComponent>): TransformDecision {
    val start = linkedSetOf<TransformComponent>()
    if (motion.displacement.getDistance() >= 5f) start += TransformComponent.Pan
    if (abs(motion.current.distance - motion.origin.distance) >= 2)
      start += TransformComponent.Scale
    if (abs(motion.rotationFromStart) >= 5) start += TransformComponent.Rotation
    return TransformDecision(
      start - active,
      pan = motion.pan,
      scale = motion.scale,
      rotation = motion.rotation,
      verticalDrag = 0f,
    )
  }
}
