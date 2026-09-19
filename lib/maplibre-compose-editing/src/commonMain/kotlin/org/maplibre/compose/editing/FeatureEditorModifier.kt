package org.maplibre.compose.editing

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.editing.internal.EditorPointerHandler
import org.maplibre.compose.editing.internal.EditorPointerSession
import org.maplibre.compose.editing.internal.PointerSample
import org.maplibre.compose.editing.internal.withLonLat
import org.maplibre.compose.editing.internal.wrapLongitude
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Routes pointer and key input on the map surface to [state].tool.
 *
 * Pass it as [MaplibreMap][org.maplibre.compose.map.MaplibreMap]'s `surfaceModifier`. Events are
 * read in the Initial pass. The map receives a pointer until the tool claims it or consumes its
 * tap; other pointers keep their map gestures. Overlay controls under the pointer block it; map
 * layers and their click handlers do not. Keys reach the tool while the map has focus; a claimed
 * press focuses the map. With [undoShortcuts], Ctrl or Meta with Z undoes and with Shift+Z or Y
 * redoes before the tool sees the key. Hit testing uses [hitRadius] for the pointer type and
 * [hitFill] for polygon interiors. Writes [FeatureEditorState.visibleBounds] as the camera moves.
 * With [enabled] false nothing is hit-tested, consumed or hovered, and a gesture in progress is
 * cancelled.
 */
public fun Modifier.featureEditor(
  state: FeatureEditorState,
  mapState: MapState,
  enabled: Boolean = true,
  hitRadius: (PointerType) -> Dp = ::defaultHitRadius,
  hitFill: Boolean = true,
  undoShortcuts: Boolean = true,
): Modifier =
  this.then(MapFeatureEditorElement(state, mapState, enabled, hitRadius, hitFill, undoShortcuts))
    .editorCursor(state, enabled)

/** 24 dp for touch, 16 dp for stylus, 12 dp otherwise. */
public fun defaultHitRadius(pointerType: PointerType): Dp =
  when (pointerType) {
    PointerType.Touch -> 24.dp
    PointerType.Stylus -> 16.dp
    else -> 12.dp
  }

internal fun Modifier.featureEditor(
  state: FeatureEditorState,
  project: (Position) -> DpOffset?,
  unproject: (DpOffset) -> Position?,
  cameraKey: () -> Any?,
  visibleBounds: () -> BoundingBox?,
  enabled: Boolean = true,
  hitRadius: (PointerType) -> Dp = ::defaultHitRadius,
  hitFill: Boolean = true,
  undoShortcuts: Boolean = true,
): Modifier =
  this.then(
      FeatureEditorElement(
        state,
        EditorMapBinding(null, project, unproject, cameraKey, visibleBounds),
        enabled,
        hitRadius,
        hitFill,
        undoShortcuts,
      )
    )
    .editorCursor(state, enabled)

private fun Modifier.editorCursor(state: FeatureEditorState, enabled: Boolean): Modifier =
  composed {
    val icon by
      remember(state, enabled) {
        derivedStateOf { if (enabled) state.tool.cursor(state) else PointerIcon.Default }
      }
    pointerHoverIcon(icon, overrideDescendants = true)
  }

/** Projection and camera reads of one map. [unproject] wraps longitudes into [-180, 180]. */
internal class EditorMapBinding(
  val key: Any?,
  val project: (Position) -> DpOffset?,
  unproject: (DpOffset) -> Position?,
  val cameraKey: () -> Any?,
  val visibleBounds: () -> BoundingBox?,
) {
  val unproject: (DpOffset) -> Position? = { screen ->
    unproject(screen)?.let { position ->
      val wrapped = wrapLongitude(position.longitude)
      if (wrapped == position.longitude) position
      else position.withLonLat(wrapped, position.latitude)
    }
  }

  companion object {
    fun of(mapState: MapState): EditorMapBinding =
      EditorMapBinding(
        key = mapState,
        project = mapState::screenLocationFromPosition,
        unproject = mapState::positionFromScreenLocation,
        cameraKey = {
          mapState.cameraPosition
          mapState.viewport
        },
        visibleBounds = {
          mapState.getVisibleBounds()?.let {
            BoundingBox(
              it.southwest.longitude,
              it.southwest.latitude,
              it.northeast.longitude,
              it.northeast.latitude,
            )
          }
        },
      )
  }
}

private data class MapFeatureEditorElement(
  val state: FeatureEditorState,
  val mapState: MapState,
  val enabled: Boolean,
  val hitRadius: (PointerType) -> Dp,
  val hitFill: Boolean,
  val undoShortcuts: Boolean,
) : ModifierNodeElement<FeatureEditorNode>() {
  override fun create() =
    FeatureEditorNode(
      state,
      EditorMapBinding.of(mapState),
      enabled,
      hitRadius,
      hitFill,
      undoShortcuts,
    )

  override fun update(node: FeatureEditorNode) {
    node.update(
      state,
      if (node.binding.key === mapState) node.binding else EditorMapBinding.of(mapState),
      enabled,
      hitRadius,
      hitFill,
      undoShortcuts,
    )
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "featureEditor"
    properties["enabled"] = enabled
    properties["hitFill"] = hitFill
    properties["undoShortcuts"] = undoShortcuts
  }
}

private data class FeatureEditorElement(
  val state: FeatureEditorState,
  val binding: EditorMapBinding,
  val enabled: Boolean,
  val hitRadius: (PointerType) -> Dp,
  val hitFill: Boolean,
  val undoShortcuts: Boolean,
) : ModifierNodeElement<FeatureEditorNode>() {
  override fun create() =
    FeatureEditorNode(state, binding, enabled, hitRadius, hitFill, undoShortcuts)

  override fun update(node: FeatureEditorNode) {
    node.update(state, binding, enabled, hitRadius, hitFill, undoShortcuts)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "featureEditor"
    properties["enabled"] = enabled
    properties["hitFill"] = hitFill
    properties["undoShortcuts"] = undoShortcuts
  }
}

internal class FeatureEditorNode(
  state: FeatureEditorState,
  binding: EditorMapBinding,
  enabled: Boolean,
  private var hitRadius: (PointerType) -> Dp,
  private var hitFill: Boolean,
  private var undoShortcuts: Boolean,
) :
  Modifier.Node(),
  PointerInputModifierNode,
  KeyInputModifierNode,
  ObserverModifierNode,
  CompositionLocalConsumerModifierNode,
  EditorPointerHandler {
  var state: FeatureEditorState = state
    private set

  var binding: EditorMapBinding = binding
    private set

  private var enabled: Boolean = enabled

  private val session =
    EditorPointerSession(
      handler = this,
      touchSlop = {
        val viewConfiguration = currentValueOf(LocalViewConfiguration)
        with(currentValueOf(LocalDensity)) { viewConfiguration.touchSlop.toDp() }
      },
      doubleTapTimeoutMillis = { currentValueOf(LocalViewConfiguration).doubleTapTimeoutMillis },
      doubleTapRadius = { hitRadius(it) },
    )
  private var longPressJob: Job? = null
  private var pressHit: EditorHit? = null
  private var origin: EditorPointer? = null
  private var previous: EditorPointer? = null

  fun update(
    state: FeatureEditorState,
    binding: EditorMapBinding,
    enabled: Boolean,
    hitRadius: (PointerType) -> Dp,
    hitFill: Boolean,
    undoShortcuts: Boolean,
  ) {
    if (state !== this.state || !enabled) endInput()
    val rebind = binding !== this.binding
    this.state = state
    this.binding = binding
    this.enabled = enabled
    this.hitRadius = hitRadius
    this.hitFill = hitFill
    this.undoShortcuts = undoShortcuts
    if (rebind && isAttached) observeCamera()
  }

  override fun onAttach() {
    observeCamera()
  }

  override fun onDetach() {
    endInput()
  }

  override fun onObservedReadsChanged() {
    observeCamera()
  }

  private fun observeCamera() {
    observeReads { binding.cameraKey() }
    binding.visibleBounds()?.let { bounds ->
      expandedVisibleBounds(state.visibleBounds, bounds)?.let { state.visibleBounds = it }
    }
  }

  /** Cancels a claimed gesture and clears the hover. */
  private fun endInput() {
    session.reset()
    cancelLongPress()
    if (state.hover != null) state.hover = null
  }

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    if (pass != PointerEventPass.Initial || !enabled) return
    when (pointerEvent.type) {
      PointerEventType.Scroll -> return
      PointerEventType.Exit -> {
        if (pointerEvent.changes.none { it.pressed || it.previousPressed }) {
          if (state.hover != null) state.hover = null
          return
        }
      }
    }
    val density = currentValueOf(LocalDensity)
    val buttons = pointerEvent.buttons()
    val modifierKeys = pointerEvent.modifierKeys()
    if (pointerEvent.changes.none { it.pressed || it.previousPressed }) {
      hover(pointerEvent, buttons, modifierKeys, density)
      return
    }
    val samples =
      pointerEvent.changes.map { change ->
        PointerSample(
          id = change.id.value,
          pressed = change.pressed,
          previousPressed = change.previousPressed,
          screen = change.position.toDp(density),
          timeMillis = change.uptimeMillis,
          pointerType = change.type,
          buttons = buttons,
          modifierKeys = modifierKeys,
        )
      }
    val consume = session.onEvent(samples)
    if (consume.isNotEmpty()) {
      pointerEvent.changes.forEach { if (it.id.value in consume) it.consume() }
    }
    if (session.longPressPending) scheduleLongPress() else cancelLongPress()
  }

  override fun onCancelPointerInput() {
    session.reset()
    cancelLongPress()
  }

  private fun hover(
    event: PointerEvent,
    buttons: Set<PointerButton>,
    modifierKeys: Set<KeyModifier>,
    density: androidx.compose.ui.unit.Density,
  ) {
    if (event.type != PointerEventType.Move && event.type != PointerEventType.Enter) return
    val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: return
    if (buttons.isNotEmpty()) return
    val screen = change.position.toDp(density)
    val position = binding.unproject(screen) ?: return
    val hit = hitAt(screen, change.type)
    if (state.hover != hit) state.hover = hit
    val pointer = EditorPointer(screen, position, change.type, buttons, modifierKeys)
    state.tool.onEvent(
      EditorEvent.Hover(pointer, hit, binding.project, binding.unproject),
      state,
    )
  }

  private fun hitAt(screen: DpOffset, type: PointerType): EditorHit? =
    state.hitTest(screen, hitRadius(type), binding.unproject, hitFill).firstOrNull()

  private fun scheduleLongPress() {
    if (longPressJob != null) return
    val timeout = currentValueOf(LocalViewConfiguration).longPressTimeoutMillis
    longPressJob = coroutineScope.launch {
      delay(timeout)
      longPressJob = null
      session.longPress()
    }
  }

  private fun cancelLongPress() {
    longPressJob?.cancel()
    longPressJob = null
  }

  private fun pointer(sample: PointerSample, position: Position): EditorPointer =
    EditorPointer(
      screen = sample.screen,
      position = position,
      pointerType = sample.pointerType,
      buttons = origin?.buttons ?: sample.buttons,
      modifierKeys = sample.modifierKeys,
    )

  override fun onPress(sample: PointerSample, step: EditStep): Boolean {
    val position = binding.unproject(sample.screen) ?: return false
    val pointer =
      EditorPointer(
        sample.screen,
        position,
        sample.pointerType,
        sample.buttons,
        sample.modifierKeys,
      )
    val hit = hitAt(sample.screen, sample.pointerType)
    pressHit = hit
    origin = pointer
    previous = pointer
    val claimed =
      state.tool.onEvent(
        EditorEvent.Press(pointer, hit, step, binding.project, binding.unproject),
        state,
      )
    if (claimed) state.gestureInProgress = true
    return claimed
  }

  override fun onDrag(sample: PointerSample, step: EditStep): Boolean {
    val position = binding.unproject(sample.screen) ?: return false
    val pointer = pointer(sample, position)
    state.tool.onEvent(
      EditorEvent.Drag(
        pointer,
        checkNotNull(origin),
        checkNotNull(previous),
        pressHit,
        step,
        binding.project,
        binding.unproject,
      ),
      state,
    )
    previous = pointer
    return true
  }

  override fun onRelease(sample: PointerSample, step: EditStep) {
    val last = checkNotNull(previous)
    val pointer = binding.unproject(sample.screen)?.let { pointer(sample, it) } ?: last
    state.gestureInProgress = false
    state.tool.onEvent(
      EditorEvent.Release(pointer, step, binding.project, binding.unproject),
      state,
    )
  }

  override fun onTap(sample: PointerSample, count: Int, step: EditStep): Boolean {
    state.gestureInProgress = false
    val position = binding.unproject(sample.screen) ?: return false
    return state.tool.onEvent(
      EditorEvent.Tap(
        pointer(sample, position),
        pressHit,
        count,
        step,
        binding.project,
        binding.unproject,
      ),
      state,
    )
  }

  override fun onLongPress(sample: PointerSample, step: EditStep) {
    state.gestureInProgress = false
    val pointer =
      binding.unproject(sample.screen)?.let { pointer(sample, it) } ?: checkNotNull(origin)
    state.tool.onEvent(
      EditorEvent.LongPress(pointer, pressHit, step, binding.project, binding.unproject),
      state,
    )
  }

  override fun onCancel(step: EditStep) {
    state.gestureInProgress = false
    state.tool.onEvent(EditorEvent.Cancel(step, binding.project, binding.unproject), state)
  }

  override fun onPreKeyEvent(event: KeyEvent): Boolean {
    if (!enabled) return false
    val down = event.type == KeyEventType.KeyDown
    if (down && event.key == Key.Escape && session.cancel()) {
      cancelLongPress()
      return true
    }
    if (down && undoShortcuts && (event.isCtrlPressed || event.isMetaPressed)) {
      val redo = (event.key == Key.Z && event.isShiftPressed) || event.key == Key.Y
      if (redo && state.canRedo) {
        state.redo()
        return true
      }
      if (!redo && event.key == Key.Z && state.canUndo) {
        state.undo()
        return true
      }
    }
    return state.tool.onEvent(
      EditorEvent.Key(
        event.key,
        event.type,
        event.modifierKeys(),
        binding.project,
        binding.unproject,
      ),
      state,
    )
  }

  override fun onKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * Returns the bounds to store for a map now showing [visible], or null to keep [current]: [visible]
 * expanded by half its size on every side when [current] is null, does not contain [visible], or is
 * more than four times its area.
 */
internal fun expandedVisibleBounds(current: BoundingBox?, visible: BoundingBox): BoundingBox? {
  val width = visible.east - visible.west
  val height = visible.north - visible.south
  if (current != null) {
    val currentWidth = current.east - current.west
    val currentArea = currentWidth * (current.north - current.south)
    val contained =
      current.contains(Position(visible.west, visible.south)) &&
        current.contains(Position(visible.east, visible.north)) &&
        width <= currentWidth
    if (contained && currentArea <= 4 * width * height * (1 + AREA_TOLERANCE)) return null
  }
  return BoundingBox(
    visible.west - width / 2,
    (visible.south - height / 2).coerceAtLeast(-90.0),
    visible.east + width / 2,
    (visible.north + height / 2).coerceAtMost(90.0),
  )
}

private const val AREA_TOLERANCE = 1e-6

private fun androidx.compose.ui.geometry.Offset.toDp(density: androidx.compose.ui.unit.Density) =
  with(density) { DpOffset(x.toDp(), y.toDp()) }

private fun PointerEvent.buttons(): Set<PointerButton> = buildSet {
  if (buttons.isPrimaryPressed) add(PointerButton.Primary)
  if (buttons.isSecondaryPressed) add(PointerButton.Secondary)
  if (buttons.isTertiaryPressed) add(PointerButton.Tertiary)
  if (buttons.isBackPressed) add(PointerButton.Back)
  if (buttons.isForwardPressed) add(PointerButton.Forward)
}

private fun PointerEvent.modifierKeys(): Set<KeyModifier> = buildSet {
  if (keyboardModifiers.isShiftPressed) add(KeyModifier.Shift)
  if (keyboardModifiers.isCtrlPressed) add(KeyModifier.Ctrl)
  if (keyboardModifiers.isAltPressed) add(KeyModifier.Alt)
  if (keyboardModifiers.isMetaPressed) add(KeyModifier.Meta)
}

private fun KeyEvent.modifierKeys(): Set<KeyModifier> = buildSet {
  if (isShiftPressed) add(KeyModifier.Shift)
  if (isCtrlPressed) add(KeyModifier.Ctrl)
  if (isAltPressed) add(KeyModifier.Alt)
  if (isMetaPressed) add(KeyModifier.Meta)
}
