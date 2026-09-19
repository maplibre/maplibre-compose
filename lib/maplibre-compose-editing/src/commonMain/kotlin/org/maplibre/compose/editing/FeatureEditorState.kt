package org.maplibre.compose.editing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.editing.internal.FeatureBoundsCache
import org.maplibre.compose.editing.internal.computeHits
import org.maplibre.compose.editing.internal.mergedPosition
import org.maplibre.compose.editing.internal.positionAt
import org.maplibre.compose.editing.internal.withVertexInserted
import org.maplibre.compose.editing.internal.withVertexMoved
import org.maplibre.compose.editing.internal.withVertexRemoved
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/** Identifies one undo step. Mutations that pass the same instance record into that step. */
public class EditStep

/**
 * Features being edited, with the active tool, selection, draft and undo history.
 *
 * All members are snapshot state. Use it from the main thread. Every feature in [features] has a
 * non-null id. Ids compare as [JsonPrimitive] values: `1`, `1.0` and `"1"` are three ids, and an id
 * round-trips through JSON unchanged. Features are immutable values; every edit replaces the entry.
 *
 * [validate] runs on each feature a mutation would store when the feature is new or its geometry
 * differs from the stored geometry. A returned message rejects the whole mutation: nothing changes,
 * [validationError] holds the message, and the call returns false or null. [load] does not
 * validate.
 *
 * @param initialFeatures Features present at creation. A feature without an id receives one from
 *   [newId].
 * @param initialTool The initial [tool].
 * @param historyLimit Number of undo steps kept. At 0 nothing is undoable; the latest step stays
 *   available to [revert] until the next step begins.
 * @param validate Returns a message that rejects the feature, or null to accept it.
 * @param newId Produces the id of a feature stored without one. Ids must be unique in [features].
 */
@Stable
public class FeatureEditorState(
  initialFeatures: List<EditorFeature> = emptyList(),
  initialTool: EditorTool = SelectTool(),
  public val historyLimit: Int = 100,
  public val validate: (EditorFeature) -> String? = { null },
  public val newId: () -> FeatureId = { JsonPrimitive(randomHexId()) },
) {
  private var featureList by mutableStateOf(withIds(initialFeatures))

  private var undoStack by mutableStateOf<List<HistoryEntry>>(emptyList())

  private var redoStack by mutableStateOf<List<HistoryEntry>>(emptyList())

  private val currentIndex = ListIndex()

  private val beforeIndex = ListIndex()

  private val boundsCache = FeatureBoundsCache()

  private var boundsCacheList: List<EditorFeature>? = null

  /** Features in rendering order. */
  public val features: List<EditorFeature>
    get() = featureList

  private var toolState by mutableStateOf(initialTool)

  /**
   * The tool that receives input. Setting it clears [activeHandle] and [hover]. It does not change
   * [draft].
   */
  public var tool: EditorTool
    get() = toolState
    set(value) {
      toolState = value
      activeHandle = null
      hover = null
    }

  private var selectionState by mutableStateOf<Set<FeatureId>>(emptySet())

  /** Ids of selected features. Ids not present in [features] are dropped. */
  public var selection: Set<FeatureId>
    get() = selectionState
    set(value) {
      selectionState = retainKnown(value)
      activeHandle = null
    }

  private var draftState by mutableStateOf<EditorDraft?>(null)

  /** Geometry under construction, or null. */
  public var draft: EditorDraft?
    get() = draftState
    set(value) {
      draftState = value
      normalizeHandles()
    }

  /**
   * Handle that keyboard actions target and that a drag moves.
   *
   * Set by tools on press or tap. Its position follows [moveVertex]. Cleared by [insertVertex],
   * [removeVertex], when its vertex no longer exists, when [selection] or [tool] changes, and by
   * [cancelDraft] for a draft vertex. A handle without a vertex is cleared only by those setters.
   */
  public var activeHandle: EditorHandle? by mutableStateOf(null)

  /** Hit under an unpressed mouse pointer. Null for touch. */
  public var hover: EditorHit? by mutableStateOf(null)
    internal set

  /** True from a press the tool claimed until that pointer is released or cancelled. */
  public var gestureInProgress: Boolean by mutableStateOf(false)
    internal set

  /**
   * Bounds the map shows, with a margin, or null without a map. Set by [Modifier.featureEditor].
   * Tools omit handles outside it.
   */
  public var visibleBounds: BoundingBox? by mutableStateOf(null)
    internal set

  /**
   * Message of the latest rejected mutation. Cleared when a mutation stores something and by
   * [cancelDraft].
   */
  public var validationError: String? by mutableStateOf(null)
    private set

  private val handlesState = derivedStateOf { tool.handles(this) }

  /**
   * Handles the current tool wants rendered and hit-tested. Derived from [tool], [features],
   * [selection], [draft] and [visibleBounds].
   */
  public val handles: List<EditorHandle>
    get() = handlesState.value

  private val undoableCount: Int
    get() = minOf(undoStack.size, historyLimit)

  /**
   * Whether [undo] does something: the draft has a position, or a step within [historyLimit] is
   * recorded.
   */
  public val canUndo: Boolean
    get() = draft?.positions?.isNotEmpty() == true || undoableCount > 0

  /** Whether [redo] does something. */
  public val canRedo: Boolean
    get() = redoStack.isNotEmpty()

  /** Whether [finishDraft] would create a feature. */
  public val canFinishDraft: Boolean
    get() = tool.canFinishDraft(this)

  /** Returns the feature with [id], or null. */
  public fun feature(id: FeatureId): EditorFeature? =
    currentIndex.of(featureList)[id]?.let { featureList[it] }

  /**
   * Returns the feature with [id] as it was before the latest step when that step was recorded
   * under [step], else the current feature. Null when [id] is unknown in both.
   */
  public fun featureBefore(step: EditStep, id: FeatureId): EditorFeature? {
    val latest = undoStack.lastOrNull()
    if (latest == null || latest.step !== step) return feature(id)
    return beforeIndex.of(latest.before)[id]?.let { latest.before[it] } ?: feature(id)
  }

  /**
   * Adds [feature] at the end and returns its id, or null when [validate] rejects it. A feature
   * without an id gets one from [newId]. Throws [IllegalArgumentException] when the id is present.
   */
  public fun add(feature: EditorFeature, undoStep: EditStep? = null): FeatureId? {
    val stored = if (feature.id == null) feature.copy(id = newId()) else feature
    val id = checkNotNull(stored.id)
    require(id !in currentIndex.of(featureList)) { "Feature id $id is already present" }
    if (!accept(listOf(stored to null))) return null
    commit(featureList + stored, undoStep)
    return id
  }

  /**
   * Replaces the feature whose id equals [feature].id.
   *
   * [validate] runs when the geometry differs from the stored one; a properties-only change is
   * stored without validation. When the geometry differs and [feature].bbox equals the stored bbox,
   * the stored copy gets a null bbox. Returns false and changes nothing when [validate] rejects
   * [feature]. Throws [IllegalArgumentException] when the id is null or unknown.
   */
  public fun replace(feature: EditorFeature, undoStep: EditStep? = null): Boolean {
    val id = requireNotNull(feature.id) { "Feature has no id" }
    val position =
      requireNotNull(currentIndex.of(featureList)[id]) { "Feature id $id is not present" }
    val existing = featureList[position]
    if (!accept(listOf(feature to existing))) return false
    val stored = withBboxRule(feature, existing)
    commit(featureList.toMutableList().also { it[position] = stored }, undoStep)
    return true
  }

  /** Removes the features with [ids] and drops them from [selection]. Unknown ids are ignored. */
  public fun remove(ids: Collection<FeatureId>, undoStep: EditStep? = null) {
    val removed = ids.toSet()
    val remaining = featureList.filter { it.id !in removed }
    if (remaining.size == featureList.size) return
    commit(remaining, undoStep)
  }

  /**
   * Applies [features] and [removeIds] as one step.
   *
   * A feature whose id is stored replaces that entry in place; a feature without an id or with an
   * unknown id is appended, and one without an id gets one from [newId]. Among duplicate ids in
   * [features] the last wins. [validate] runs on each new feature and each feature whose geometry
   * differs from the stored one; when it rejects one, nothing changes and null is returned. Undo
   * restores whole lists: undoing a step recorded before this call also reverts it. Returns the ids
   * of [features] in order.
   */
  public fun update(
    features: List<EditorFeature> = emptyList(),
    removeIds: Collection<FeatureId> = emptyList(),
    undoStep: EditStep? = null,
  ): List<FeatureId>? {
    val list = featureList.toMutableList()
    val index = HashMap(currentIndex.of(featureList))
    val ids = ArrayList<FeatureId>(features.size)
    val checks = ArrayList<Pair<EditorFeature, EditorFeature?>>(features.size)
    for (feature in features) {
      val withId = if (feature.id == null) feature.copy(id = newId()) else feature
      val id = checkNotNull(withId.id)
      ids += id
      val position = index[id]
      if (position == null) {
        checks += withId to null
        index[id] = list.size
        list += withId
      } else {
        val existing = featureList.getOrNull(position)?.takeIf { it.id == id }
        checks += withId to existing
        list[position] = if (existing == null) withId else withBboxRule(withId, existing)
      }
    }
    if (!accept(checks)) return null
    val removed = removeIds.toSet()
    val result = if (removed.isEmpty()) list else list.filter { it.id !in removed }
    if (features.isEmpty() && result.size == featureList.size) return ids
    commit(result, undoStep)
    return ids
  }

  /**
   * Replaces the whole feature list without recording a step and clears the undo history.
   *
   * [draft] and [tool] are unchanged. [selection] drops ids that no longer exist. [activeHandle]
   * and [hover] are cleared when their feature or vertex no longer exists. Features are not
   * validated. A feature without an id gets one from [newId]; duplicate ids throw
   * [IllegalArgumentException]. Use [update] to merge changes while keeping the history.
   */
  public fun load(features: List<EditorFeature>) {
    featureList = withIds(features)
    undoStack = emptyList()
    redoStack = emptyList()
    normalize()
  }

  /**
   * Moves the vertex at [ref] to [position]. Ring closure follows. Altitude and further coordinate
   * values of the existing position are kept when [position] has only longitude and latitude. The
   * feature and geometry bbox become null. Updates [activeHandle] when it addresses the same
   * vertex. Returns false when [validate] rejects the result.
   */
  public fun moveVertex(ref: VertexRef, position: Position, undoStep: EditStep? = null): Boolean {
    val id = ref.featureId
    if (id == null) {
      val current = draft ?: return false
      val index = ref.path.singleOrNull() ?: return false
      val existing = current.positions.getOrNull(index) ?: return false
      val merged = mergedPosition(existing, position)
      draft =
        current.copy(positions = current.positions.toMutableList().also { it[index] = merged })
      followActiveHandle(ref, merged)
      return true
    }
    val slot = currentIndex.of(featureList)[id] ?: return false
    val feature = featureList[slot]
    val existing = feature.geometry.positionAt(ref.path) ?: return false
    val merged = mergedPosition(existing, position)
    val geometry = feature.geometry.withVertexMoved(ref.path, merged) ?: return false
    val stored = feature.copy(geometry = geometry, bbox = null)
    if (!accept(listOf(stored to feature))) return false
    commit(featureList.toMutableList().also { it[slot] = stored }, undoStep)
    followActiveHandle(ref, merged)
    return true
  }

  /**
   * Inserts [position] before the vertex at [ref]. An index equal to the ring or line length
   * appends. Clears [activeHandle]. Returns false when [validate] rejects the result.
   */
  public fun insertVertex(
    ref: VertexRef,
    position: Position,
    undoStep: EditStep? = null,
  ): Boolean {
    val id = ref.featureId
    if (id == null) {
      val current = draft ?: return false
      val index = ref.path.singleOrNull() ?: return false
      if (index < 0 || index > current.positions.size) return false
      draft =
        current.copy(positions = current.positions.toMutableList().also { it.add(index, position) })
      activeHandle = null
      return true
    }
    val slot = currentIndex.of(featureList)[id] ?: return false
    val feature = featureList[slot]
    val geometry = feature.geometry.withVertexInserted(ref.path, position) ?: return false
    val stored = feature.copy(geometry = geometry, bbox = null)
    if (!accept(listOf(stored to feature))) return false
    commit(featureList.toMutableList().also { it[slot] = stored }, undoStep)
    activeHandle = null
    return true
  }

  /**
   * Removes the vertex at [ref] and clears [activeHandle].
   *
   * Returns false and changes nothing when a line would keep fewer than 2 positions, a ring fewer
   * than 3 distinct positions, a point would lose its position, or [validate] rejects the result.
   */
  public fun removeVertex(ref: VertexRef, undoStep: EditStep? = null): Boolean {
    val id = ref.featureId
    if (id == null) {
      val current = draft ?: return false
      val index = ref.path.singleOrNull() ?: return false
      if (index !in current.positions.indices) return false
      draft =
        current.copy(positions = current.positions.toMutableList().also { it.removeAt(index) })
      activeHandle = null
      return true
    }
    val slot = currentIndex.of(featureList)[id] ?: return false
    val feature = featureList[slot]
    val geometry = feature.geometry.withVertexRemoved(ref.path) ?: return false
    val stored = feature.copy(geometry = geometry, bbox = null)
    if (!accept(listOf(stored to feature))) return false
    commit(featureList.toMutableList().also { it[slot] = stored }, undoStep)
    activeHandle = null
    return true
  }

  /**
   * Removes the last draft position when the draft has one, else restores [features] from before
   * the latest step. [selection] drops ids that no longer exist. Draft removals are not redoable.
   */
  public fun undo() {
    if (draft?.positions?.isNotEmpty() == true) {
      removeLastDraftPosition()
      return
    }
    if (undoableCount == 0) return
    val entry = undoStack.last()
    undoStack = undoStack.dropLast(1)
    redoStack = redoStack + entry
    featureList = entry.before
    normalize()
  }

  /** Reapplies the step [undo] took back, when one exists. */
  public fun redo() {
    val entry = redoStack.lastOrNull() ?: return
    redoStack = redoStack.dropLast(1)
    pushUndo(entry)
    featureList = entry.after
    normalize()
  }

  /**
   * Reverts the latest step when it was recorded under [step] and drops it from history. Returns
   * whether it did.
   */
  public fun revert(step: EditStep): Boolean {
    val entry = undoStack.lastOrNull() ?: return false
    if (entry.step !== step) return false
    undoStack = undoStack.dropLast(1)
    featureList = entry.before
    normalize()
    return true
  }

  /** Adds [position] to the draft through [tool]. Returns false when the tool does not draw. */
  public fun placeDraftPosition(position: Position): Boolean =
    tool.placeDraftPosition(this, position)

  /** Removes the last draft position. An empty draft becomes null. */
  public fun removeLastDraftPosition() {
    val current = draft ?: return
    draft =
      if (current.positions.size <= 1) null
      else current.copy(positions = current.positions.dropLast(1))
    normalizeHandles()
  }

  /**
   * Commits the draft through [tool]. Returns the new feature id, or null when nothing was created.
   */
  public fun finishDraft(): FeatureId? = tool.finishDraft(this)

  /** Discards [draft] and clears [validationError]. */
  public fun cancelDraft() {
    draft = null
    validationError = null
    if (activeHandle?.vertex?.let { it.featureId == null } == true) activeHandle = null
    if ((hover as? HandleHit)?.handle?.vertex?.let { it.featureId == null } == true) hover = null
  }

  /**
   * Hits at [screen], nearest handle first, then features from the selection and the top of
   * [features] down. The tolerance is [radius] unprojected at [screen]. With [fill] false, polygons
   * hit only on their outline. Returns an empty list without a viewport. [Modifier.featureEditor]
   * uses the first element. From a map click callback: `editor.hitTest(click.offset, 12.dp,
   * map::positionFromScreenLocation).firstOrNull()`.
   */
  public fun hitTest(
    screen: DpOffset,
    radius: Dp,
    unproject: (DpOffset) -> Position?,
    fill: Boolean = true,
  ): List<EditorHit> {
    val list = featureList
    if (boundsCacheList !== list) {
      boundsCache.retain(currentIndex.of(list).keys)
      boundsCacheList = list
    }
    val selected = selectionState
    val candidates =
      if (selected.isEmpty()) list.asReversed()
      else
        list.asReversed().let { r ->
          r.filter { it.id in selected } + r.filter { it.id !in selected }
        }
    return computeHits(handles, candidates, screen, radius, unproject, fill, boundsCache)
  }

  /** The current features as a FeatureCollection. */
  public fun toFeatureCollection(): FeatureCollection<Geometry, JsonObject?> =
    FeatureCollection(featureList)

  private fun withIds(features: List<EditorFeature>): List<EditorFeature> {
    val seen = HashSet<FeatureId>(features.size)
    return features.map { feature ->
      val stored = if (feature.id == null) feature.copy(id = newId()) else feature
      require(seen.add(checkNotNull(stored.id))) { "Duplicate feature id ${stored.id}" }
      stored
    }
  }

  private fun retainKnown(ids: Set<FeatureId>): Set<FeatureId> {
    val index = currentIndex.of(featureList)
    return if (ids.all { it in index }) ids else ids.filterTo(LinkedHashSet()) { it in index }
  }

  /** Runs [validate] on each new or geometrically changed feature; records the first message. */
  private fun accept(checks: List<Pair<EditorFeature, EditorFeature?>>): Boolean {
    for ((candidate, existing) in checks) {
      if (existing != null && !geometryDiffers(candidate, existing)) continue
      val message = validate(candidate) ?: continue
      validationError = message
      return false
    }
    return true
  }

  private fun geometryDiffers(a: EditorFeature, b: EditorFeature): Boolean =
    a.geometry !== b.geometry && a.geometry != b.geometry

  private fun withBboxRule(candidate: EditorFeature, existing: EditorFeature): EditorFeature =
    if (
      geometryDiffers(candidate, existing) &&
        candidate.bbox == existing.bbox &&
        candidate.bbox != null
    )
      candidate.copy(bbox = null)
    else candidate

  private fun commit(list: List<EditorFeature>, undoStep: EditStep?) {
    val latest = undoStack.lastOrNull()
    if (undoStep != null && latest != null && latest.step === undoStep) {
      undoStack = undoStack.dropLast(1) + latest.copy(after = list)
    } else {
      pushUndo(HistoryEntry(undoStep, featureList, list))
    }
    redoStack = emptyList()
    featureList = list
    validationError = null
    normalize()
  }

  // The latest entry is kept at historyLimit 0 so revert and gesture cancel still work.
  private fun pushUndo(entry: HistoryEntry) {
    undoStack = (undoStack + entry).takeLast(maxOf(historyLimit, 1))
  }

  private fun normalize() {
    val index = currentIndex.of(featureList)
    if (!selectionState.all { it in index }) {
      selectionState = selectionState.filterTo(LinkedHashSet()) { it in index }
    }
    normalizeHandles()
  }

  private fun normalizeHandles() {
    activeHandle?.vertex?.let { if (!vertexExists(it)) activeHandle = null }
    when (val hit = hover) {
      is HandleHit -> hit.handle.vertex?.let { if (!vertexExists(it)) hover = null }
      is FeatureHit -> if (hit.featureId !in currentIndex.of(featureList)) hover = null
      null -> Unit
    }
  }

  private fun vertexExists(ref: VertexRef): Boolean {
    val id = ref.featureId
    if (id == null) {
      val index = ref.path.singleOrNull() ?: return false
      return draft?.positions?.indices?.contains(index) == true
    }
    return feature(id)?.geometry?.positionAt(ref.path) != null
  }

  private fun followActiveHandle(ref: VertexRef, position: Position) {
    val handle = activeHandle ?: return
    if (handle.vertex == ref) activeHandle = handle.copy(position = position)
  }

  public companion object {
    /**
     * Saves [features], [selection], [draft] and, when [tool] is a [DrawTool], its options as JSON.
     * Other tools and the history are not saved. A restored state receives the saved [DrawTool]
     * with `nextTool = initialTool` (null when it was null), else [initialTool], passed through
     * [restoreTool] with the restored draft.
     *
     * Android saved state above about 500 KB in total fails with TransactionTooLargeException. Hold
     * large collections outside saved state, for example in a ViewModel, and call [load] after
     * loading.
     */
    public fun saver(
      initialTool: EditorTool = SelectTool(),
      historyLimit: Int = 100,
      validate: (EditorFeature) -> String? = { null },
      newId: () -> FeatureId = { JsonPrimitive(randomHexId()) },
      restoreTool: (restored: EditorTool, draft: EditorDraft?) -> EditorTool = { restored, _ ->
        restored
      },
    ): Saver<FeatureEditorState, Any> =
      Saver(
        save = { state ->
          Json.encodeToString(
            SavedEditorState(
              features = state.toFeatureCollection().toJson(),
              selection = state.selection.toList(),
              draft = state.draft,
              drawTool = (state.tool as? DrawTool)?.let(SavedDrawTool::of),
            )
          )
        },
        restore = { value ->
          val saved = Json.decodeFromString<SavedEditorState>(value as String)
          val features = FeatureCollection.fromJson<Geometry, JsonObject?>(saved.features).features
          val restored = saved.drawTool?.toTool(initialTool) ?: initialTool
          FeatureEditorState(
              initialFeatures = features,
              initialTool = restoreTool(restored, saved.draft),
              historyLimit = historyLimit,
              validate = validate,
              newId = newId,
            )
            .apply {
              draft = saved.draft
              selection = saved.selection.toSet()
            }
        },
      )
  }
}

/**
 * Remembers a [FeatureEditorState] with [FeatureEditorState.saver] across recomposition and
 * configuration changes.
 *
 * @param initialFeatures Features present the first time the state is created.
 * @param initialTool The tool of a new state, and the tool a restored state returns to when no
 *   [DrawTool] was saved.
 * @param restoreTool Maps the restored tool and draft to the tool a restored state uses.
 */
@Composable
public fun rememberFeatureEditorState(
  initialFeatures: List<EditorFeature> = emptyList(),
  initialTool: EditorTool = SelectTool(),
  historyLimit: Int = 100,
  validate: (EditorFeature) -> String? = { null },
  newId: () -> FeatureId = { JsonPrimitive(randomHexId()) },
  restoreTool: (restored: EditorTool, draft: EditorDraft?) -> EditorTool = { restored, _ ->
    restored
  },
): FeatureEditorState =
  rememberSaveable(
    saver = FeatureEditorState.saver(initialTool, historyLimit, validate, newId, restoreTool)
  ) {
    FeatureEditorState(initialFeatures, initialTool, historyLimit, validate, newId)
  }

internal fun randomHexId(): String {
  val digits = "0123456789abcdef"
  return buildString(16) { repeat(16) { append(digits[Random.nextInt(16)]) } }
}

private class HistoryEntry(
  val step: EditStep?,
  val before: List<EditorFeature>,
  val after: List<EditorFeature>,
) {
  fun copy(after: List<EditorFeature>): HistoryEntry = HistoryEntry(step, before, after)
}

/** Id-to-index map for a list, rebuilt when a different list instance is asked for. */
private class ListIndex {
  private var list: List<EditorFeature>? = null
  private var map: Map<FeatureId, Int> = emptyMap()

  fun of(features: List<EditorFeature>): Map<FeatureId, Int> {
    if (list !== features) {
      val built = HashMap<FeatureId, Int>(features.size)
      features.forEachIndexed { i, feature -> built[checkNotNull(feature.id)] = i }
      map = built
      list = features
    }
    return map
  }
}

@Serializable
private class SavedEditorState(
  val features: String,
  val selection: List<JsonPrimitive>,
  val draft: EditorDraft?,
  val drawTool: SavedDrawTool?,
)

// The DrawTool options the saver stores. DrawTool's constructor parameters map onto these fields;
// nextTool is saved only as present or absent and restored as the saver's initialTool.
@Serializable
internal class SavedDrawTool(
  val shape: DrawShape,
  val properties: JsonObject?,
  val hasNextTool: Boolean,
  val finishOnDoubleTap: Boolean,
  val finishOnVertexTap: Boolean,
  val replaceExisting: Boolean,
  val placeOnTap: Boolean,
  val draftHandles: Boolean,
) {
  fun toTool(nextTool: EditorTool): DrawTool =
    DrawTool(
      shape = shape,
      properties = properties,
      nextTool = if (hasNextTool) nextTool else null,
      finishOnDoubleTap = finishOnDoubleTap,
      finishOnVertexTap = finishOnVertexTap,
      replaceExisting = replaceExisting,
      placeOnTap = placeOnTap,
      draftHandles = draftHandles,
    )

  companion object {
    fun of(tool: DrawTool): SavedDrawTool =
      SavedDrawTool(
        shape = tool.shape,
        properties = tool.properties,
        hasNextTool = tool.nextTool != null,
        finishOnDoubleTap = tool.finishOnDoubleTap,
        finishOnVertexTap = tool.finishOnVertexTap,
        replaceExisting = tool.replaceExisting,
        placeOnTap = tool.placeOnTap,
        draftHandles = tool.draftHandles,
      )
  }
}
