package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.distinctUntilChanged
import org.maplibre.compose.demoapp.DefaultMapControls
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoBoundsPadding
import org.maplibre.compose.demoapp.DemoControlSize
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.center
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.editing.EditorColors
import org.maplibre.compose.editing.EditorDraftLayers
import org.maplibre.compose.editing.EditorFeatureLayers
import org.maplibre.compose.editing.EditorHandleLayers
import org.maplibre.compose.editing.featureEditor
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.turf.measurement.computeBbox

/** Draws shapes and measures and reshapes them live with spatial-k turf. */
object FeatureEditingDemo : Demo {
  override val name = "Feature editing"
  override val description = "Draw shapes, then measure and reshape them live with spatial-k turf."
  override val destination =
    DemoDestination.FitBounds(Presets.goldenGatePark.geometry.computeBbox(), EditingFitPadding)
  override val pointerPin =
    DemoPointerPin(Presets.goldenGatePark.geometry.computeBbox().center, destination)

  private val state = FeatureEditingState()

  @Composable
  override fun surfaceModifier(mapState: MapState): Modifier =
    Modifier.focusRequester(state.mapFocus).featureEditor(state.editor, mapState)

  @Composable
  override fun MapContent(style: DemoStyle) {
    val scheme = MaterialTheme.colorScheme
    val editor = state.editor
    val rejected = editor.validationError != null
    val colors =
      remember(scheme, rejected) {
        EditorColors(
          accent = scheme.primary,
          activeHandleFill = if (rejected) scheme.error else scheme.primary,
        )
      }
    Anchor.Below({ it.type == "symbol" }) {
      EditorFeatureLayers(editor, colors, idPrefix = LAYER_PREFIX)
      FrameLayers(state)
      StationLayers(state)
      SimplifyGhostLayers(state)
      CirclePreviewLayers(state)
      EditorDraftLayers(editor, colors, idPrefix = LAYER_PREFIX)
    }
    Anchor.Top {
      SnapLayers(state)
      EditorHandleLayers(editor, colors, idPrefix = LAYER_PREFIX, handleRadius = 6.dp)
      FrameHandleLayers(state)
    }
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    val demo = this@FeatureEditingDemo.state
    DefaultMapControls(controls)
    LaunchedEffect(demo) {
      snapshotFlow { demo.editor.selection }.collect { demo.stationFraction = 0.5 }
    }
    LaunchedEffect(demo, state.mapState) {
      snapshotFlow { state.mapState.viewport?.metersPerDpAtTarget }
        .distinctUntilChanged()
        .collect { scale ->
          if (scale != null && scale > 0) demo.metersPerDp = roundToSignificant(scale, 3)
        }
    }
    EditorHintBar(demo)
    val labels = rememberShapeLabelEntries(demo)
    EdgeLabels(demo, labels)
    ShapeLabels(demo, labels)
    DraftLabel(demo)
    TransformReadout(demo)
    ValidationTooltip(demo)
  }

  @Composable
  override fun PeekPanel(state: DemoAppState) {
    ToolRow(this.state)
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    val demo = this.state
    SectionHeader("Measurements")
    MeasurementCard(demo)
    SectionHeader("Shape")
    ShapeSection(demo, state)
    SectionHeader("Shapes")
    ShapesList(demo, state)
    SectionHeader("Presets")
    PresetsSection(demo, state)
  }
}

private const val LAYER_PREFIX = "shape"

/**
 * The fit padding for every camera flight in this demo. The right inset clears the shell's button
 * column, the frame around the fitted shape and half a handle disc, so the Rotate and Scale handles
 * on the frame's right corners stay reachable on phones.
 */
internal val EditingFitPadding =
  DemoBoundsPadding.copy(right = MapOverlay.Spacing + DemoControlSize + FramePadding + 16.dp)

private fun roundToSignificant(value: Double, digits: Int): Double {
  if (value == 0.0) return 0.0
  val magnitude = 10.0.pow(digits - 1 - floor(log10(abs(value))))
  return (value * magnitude).roundToLong() / magnitude
}
