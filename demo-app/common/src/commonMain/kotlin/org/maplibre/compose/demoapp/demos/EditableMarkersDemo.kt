package org.maplibre.compose.demoapp.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.flyTo
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.delete_24px
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal class EditableMarker(val id: Int, position: Position, label: String) {
  var position by mutableStateOf(position)
  var label by mutableStateOf(label)
  var removing by mutableStateOf(false)
  var bounce by mutableStateOf(0)
  var color by mutableStateOf(MarkerColor.Theme)
}

/** Editable places with scalable labels and a geographically anchored Compose editor. */
object EditableMarkersDemo : Demo {
  override val name = "Editable markers"
  override val description = "A little collection of places worth keeping."
  override val destination = DemoDestination.None

  private val markers = mutableStateListOf<EditableMarker>()
  private var nextId = 1
  private var textScale by mutableStateOf(1f)

  // Editing and dragging are separate: moving a pin must not open its editor.
  private var editingId by mutableStateOf<Int?>(null)
  private var draggingId by mutableStateOf<Int?>(null)
  private var hoveredId by mutableStateOf<Int?>(null)
  private var pressedId by mutableStateOf<Int?>(null)
  private var dragTilt by mutableStateOf(0f)
  private var overTrash by mutableStateOf(false)
  private var trashNeedsExit by mutableStateOf(false)

  // Pointer events are map-local; Compose overlay bounds are in root coordinates.
  private var trashBounds: Rect? = null
  private var mapSize by mutableStateOf(IntSize.Zero)
  private var mapOrigin = Offset.Zero
  private val editorBounds = mutableMapOf<Int, Rect>()

  private fun select(marker: EditableMarker) {
    if (marker.removing) return
    editingId = marker.id
    marker.bounce++
  }

  private fun remove(marker: EditableMarker) {
    if (marker.removing) return
    marker.removing = true
    if (editingId == marker.id) editingId = null
    if (hoveredId == marker.id) hoveredId = null
  }

  private fun endGesture() {
    pressedId = null
    draggingId = null
    overTrash = false
    trashNeedsExit = false
    dragTilt = 0f
  }

  override fun interactions(mapState: MapState) = MapInteractions {
    callbacks {
      click {
        onUnhandled { event ->
          // Dismiss first. Creating a new pin takes a separate tap on the empty map.
          if (editingId != null) editingId = null
          else if (draggingId == null) {
            event.position?.let { position ->
              val marker = EditableMarker(nextId++, position, "New place")
              markers.add(marker)
              editingId = marker.id
            }
          }
          ClickResult.Consume
        }
      }
    }
  }

  @Composable
  override fun mapModifier(mapState: MapState): Modifier =
    Modifier.onGloballyPositioned {
        mapOrigin = it.positionInRoot()
        mapSize = it.size
      }
      .pointerHoverIcon(
        if (hoveredId != null || draggingId != null) PointerIcon.Hand else PointerIcon.Default
      )
      .pointerInput(mapState) {
        fun hit(position: Offset): EditableMarker? {
          if (editorBounds.values.any { it.contains(position + mapOrigin) }) return null
          val point = DpOffset(position.x.toDp(), position.y.toDp())
          return markers
            .filterNot { it.removing }
            .mapNotNull { marker ->
              val anchor =
                mapState.screenLocationFromPosition(marker.position) ?: return@mapNotNull null
              markerHitDistance(point, anchor)?.let { marker to it }
            }
            .minByOrNull { it.second }
            ?.first
        }
        try {
          awaitPointerEventScope {
            var captured: EditableMarker? = null
            var pointer: PointerId? = null
            var trashTarget: MarkerTrashTarget? = null
            var down = Offset.Zero
            var anchor = DpOffset.Zero
            var swallowing = false
            while (true) {
              // Reserve pin presses before the map's camera recognizer sees them.
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull() ?: continue
              if (event.type == PointerEventType.Exit) hoveredId = null
              else if (change.type == PointerType.Mouse && !change.pressed) {
                hoveredId = hit(change.position)?.id
              }

              // After a second finger interrupts a pin drag, consume the rest of that gesture.
              if (swallowing) {
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) swallowing = false
                continue
              }

              // Capture only presses on a pin. All other input reaches the map camera.
              if (
                captured == null &&
                  event.type == PointerEventType.Press &&
                  event.changes.count { it.pressed } == 1 &&
                  !change.isConsumed &&
                  (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed)
              ) {
                val marker = hit(change.position)
                val screen = marker?.let { mapState.screenLocationFromPosition(it.position) }
                if (marker != null && screen != null) {
                  captured = marker
                  pointer = change.id
                  down = change.position
                  trashTarget = MarkerTrashTarget(down + mapOrigin)
                  anchor = screen
                  pressedId = marker.id
                  change.consume()
                }
              }

              val marker = captured ?: continue
              val tracked = event.changes.firstOrNull { it.id == pointer }
              if (tracked == null || event.changes.count { it.pressed } > 1 || marker.removing) {
                captured = null
                endGesture()
                swallowing = event.changes.any { it.pressed }
                event.changes.forEach { it.consume() }
                continue
              }

              // Keep a press as a tap until it crosses the platform touch slop.
              tracked.consume()
              val distance = tracked.position - down
              if (
                tracked.pressed &&
                  draggingId == null &&
                  distance.getDistance() > viewConfiguration.touchSlop
              ) {
                editingId = null
                draggingId = marker.id
                pressedId = null
              }

              // Preserve the grab offset so the pin does not jump to the finger.
              if (draggingId == marker.id) {
                overTrash = trashTarget?.update(tracked.position + mapOrigin, trashBounds) == true
                trashNeedsExit = trashTarget?.needsExit == true
                mapState
                  .positionFromScreenLocation(
                    anchor + DpOffset(distance.x.toDp(), distance.y.toDp())
                  )
                  ?.let { marker.position = it }
                dragTilt =
                  ((tracked.position.x - tracked.previousPosition.x) * 0.7f).coerceIn(-18f, 18f)
              }

              // Release either finishes a move/delete or opens the editor for a tap.
              if (!tracked.pressed) {
                if (draggingId == marker.id) {
                  if (overTrash) remove(marker) else marker.bounce++
                } else select(marker)
                captured = null
                pointer = null
                endGesture()
              }
            }
          }
        } finally {
          hoveredId = null
          endGesture()
        }
      }

  @Composable
  override fun MapContent(style: DemoStyle) {
    // Override only these layers: the slider represents an absolute accessibility scale.
    CompositionLocalProvider(
      LocalDensity provides Density(LocalDensity.current.density, textScale)
    ) {
      val theme = MaterialTheme.colorScheme
      for (marker in markers) key(marker.id) {
        val motion =
          rememberMarkerMotion(
            marker = marker,
            selected = editingId == marker.id,
            hovered = hoveredId == marker.id,
            pressed = pressedId == marker.id,
            dragging = draggingId == marker.id,
            overTrash = overTrash,
            dragTilt = dragTilt,
            onRemoved = { markers.remove(marker) },
          )
        // Compose drives each frame, so the map must not add another transition.
        val instant = TransitionOptions(Duration.ZERO)

        val colors = markerColorScheme(marker.color)
        val source =
          rememberGeoJsonSource(
            GeoJsonData.Features(Feature(geometry = Point(marker.position), properties = null)),
            options = GeoJsonOptions(synchronousUpdate = true),
          )

        // A soft shadow makes the lift during hover and drag visible against the map.
        CircleLayer(
          id = "editable-marker-shadow-${marker.id}",
          source = source,
          radius = const(motion.shadowRadius.dp),
          color = const(theme.scrim),
          opacity = const(motion.alpha * 0.18f),
          blur = const(0.7f),
          opacityTransition = instant,
          radiusTransition = instant,
        )

        // The halo marks selection without changing the pin's chosen color.
        CircleLayer(
          id = "editable-marker-halo-${marker.id}",
          source = source,
          radius = const((10f + 3f * motion.highlight).dp),
          color = const(colors.primary),
          opacity = const(motion.alpha * motion.highlight.coerceIn(0f, 1f) * 0.12f),
          strokeColor = const(colors.primary),
          strokeWidth = const(1.5.dp),
          strokeOpacity = const(motion.alpha * motion.highlight.coerceIn(0f, 1f) * 0.65f),
          radiusTransition = instant,
          opacityTransition = instant,
          strokeOpacityTransition = instant,
        )

        // Keep the icon and its live label in one symbol layer.
        SymbolLayer(
          id = "editable-marker-${marker.id}",
          source = source,
          iconImage = image(rememberMarkerPainter(colors), size = DpSize(40.dp, 48.dp)),
          iconAnchor = const(SymbolAnchor.Bottom),
          iconSize = const(motion.scale),
          iconRotate = const(motion.rotation),
          iconRotationAlignment = const(IconRotationAlignment.Viewport),
          iconPitchAlignment = const(IconPitchAlignment.Viewport),
          iconTranslate = offset(0.dp, motion.offsetY.dp),
          iconTranslateAnchor = const(TranslateAnchor.Viewport),
          iconTranslateTransition = instant,
          iconOpacity = const(motion.alpha),
          iconOpacityTransition = instant,
          iconAllowOverlap = const(true),
          iconIgnorePlacement = const(true),
          textField = const(marker.label),
          textFont = const(style.textFont),
          textSize = const(14.sp),
          textAnchor = const(SymbolAnchor.Top),
          textOffset = textOffset(0.dp, 12.dp),
          textColor = const(theme.onSurface),
          textHaloColor = const(theme.surface),
          textHaloWidth = const(2.dp),
          textOpacity = const(motion.alpha),
          textOpacityTransition = instant,
          textAllowOverlap = const(true),
          textIgnorePlacement = const(true),
          onClick = {
            select(marker)
            ClickResult.Consume
          },
        )
      }
    }
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    AnimatedVisibility(
      visible = draggingId != null,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
      enter =
        fadeIn(tween(120)) +
          scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.6f)),
      exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
    ) {
      DisposableEffect(Unit) { onDispose { trashBounds = null } }
      val targetScale by
        animateFloatAsState(if (overTrash) 1.1f else 1f, spring(dampingRatio = 0.5f))
      val targetColor by
        animateColorAsState(
          if (overTrash) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.surfaceContainerHigh
        )
      val targetContent by
        animateColorAsState(
          if (overTrash) MaterialTheme.colorScheme.onError
          else MaterialTheme.colorScheme.onSurfaceVariant
        )
      Surface(
        modifier =
          Modifier.width(200.dp)
            .onGloballyPositioned { trashBounds = it.boundsInRoot() }
            .graphicsLayer {
              scaleX = targetScale
              scaleY = targetScale
            },
        shape = CircleShape,
        color = targetColor,
        contentColor = targetContent,
        shadowElevation = 6.dp,
      ) {
        Row(
          Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(painterResource(Res.drawable.delete_24px), contentDescription = null)
          Text(
            when {
              overTrash -> "Release to delete"
              trashNeedsExit -> "Move away first"
              else -> "Drag to delete"
            },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }

    // Keep the editor inside the usable map, flipping below pins near the top edge.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val left =
      with(density) { contentWindowInsets.getLeft(this, layoutDirection).toDp().value } + 8f
    val right =
      with(density) {
        (mapSize.width - contentWindowInsets.getRight(this, layoutDirection)).toDp().value
      } - 8f
    val top = with(density) { contentWindowInsets.getTop(this).toDp().value } + 8f
    val editorWidth = (right - left).coerceIn(0f, 272f)

    for (marker in markers) key(marker.id) {
      // The overlay tracks camera frames; use the same projection to keep the editor on screen.
      val screen =
        remember(marker.position, mapState.cameraPosition, mapSize) {
          mapState.screenLocationFromPosition(marker.position)
        }
      var editorHeight by remember { mutableStateOf(66f) }
      val shift = screen?.let { markerEditorShift(it.x.value, editorWidth, left, right) } ?: 0f
      val below = screen != null && screen.y.value - 76f - editorHeight < top

      AnimatedVisibility(
        visible = editingId == marker.id && draggingId == null && !marker.removing,
        modifier =
          Modifier.placedAt(
              marker.position,
              if (below) Alignment.TopCenter else Alignment.BottomCenter,
            )
            .padding(
              top = if (below) (32f + 14f * textScale).dp else 0.dp,
              bottom = if (below) 0.dp else 76.dp,
            ),
        enter =
          fadeIn(tween(130)) +
            scaleIn(
              initialScale = 0.75f,
              transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f),
              animationSpec = spring(dampingRatio = 0.58f, stiffness = 400f),
            ),
        exit =
          fadeOut(tween(120)) +
            scaleOut(
              targetScale = 0.85f,
              transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f),
              animationSpec = tween(120),
            ),
      ) {
        DisposableEffect(marker.id) { onDispose { editorBounds.remove(marker.id) } }
        val surface = MaterialTheme.colorScheme.surfaceContainerLowest
        Column(
          Modifier.width(editorWidth.dp).absoluteOffset(x = shift.dp).onGloballyPositioned {
            editorBounds[marker.id] = it.boundsInRoot()
            editorHeight = with(density) { it.size.height.toDp().value }
          },
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (below) EditorPointer(surface, shift, below = true)

          MarkerEditor(marker, onClose = { editingId = null })

          if (!below) EditorPointer(surface, shift, below = false)
        }
      }
    }
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    val scope = rememberCoroutineScope()
    EditableMarkersPanel(
      markers = markers,
      selectedId = editingId,
      textScale = textScale,
      onTextScaleChange = { textScale = it },
      onSelect = { marker ->
        select(marker)
        scope.launch {
          state.mapState.flyTo(
            DemoDestination.ExactCamera(
              state.mapState.cameraPosition.copy(target = marker.position)
            )
          )
        }
      },
      onRemove = ::remove,
    )
  }
}

// The tip is the geographic anchor. Hit the pin body, with padding for small touch targets.
internal fun markerHitDistance(point: DpOffset, anchor: DpOffset): Float? {
  val padding = 10f
  val x = (point.x - anchor.x).value / (20f + padding)
  val y = ((point.y - anchor.y).value + 24f) / (24f + padding)
  return (x * x + y * y).takeIf { it <= 1f }
}

@Composable
private fun EditorPointer(color: Color, shift: Float, below: Boolean) {
  Canvas(Modifier.absoluteOffset(x = (-shift).dp).size(20.dp, 10.dp)) {
    drawPath(
      Path().apply {
        val base = if (below) size.height else 0f
        val tip = if (below) 0f else size.height
        moveTo(0f, base)
        lineTo(size.width, base)
        lineTo(size.width / 2, tip)
        close()
      },
      color,
    )
  }
}

internal fun markerEditorShift(anchorX: Float, width: Float, left: Float, right: Float): Float {
  if (right - left < width) return (left + right) / 2f - anchorX
  return anchorX.coerceIn(left + width / 2f, right - width / 2f) - anchorX
}
