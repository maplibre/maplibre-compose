package org.maplibre.compose.editing

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Position

/**
 * A position inside a geometry.
 *
 * [featureId] null addresses [FeatureEditorState.draft]. [path] indexes nested coordinate lists,
 * innermost last: Point `[]`, LineString and MultiPoint `[i]`, Polygon `[ring, i]`, MultiLineString
 * `[part, i]`, MultiPolygon `[part, ring, i]`, draft `[i]`. Ring indices range over distinct
 * positions; the closing position is not addressable. Paths after an inserted or removed vertex
 * shift by one; [FeatureEditorState.insertVertex] and [FeatureEditorState.removeVertex] are the
 * only calls that shift them.
 */
public data class VertexRef(val featureId: FeatureId?, val path: List<Int>)

/** Kind of an [EditorHandle]. Tools may define their own kinds. */
public interface HandleKind {
  /** A vertex. Drags move it. */
  public object Vertex : HandleKind

  /** The midpoint of a segment. A drag inserts a vertex at [EditorHandle.vertex] and moves it. */
  public object Midpoint : HandleKind
}

/**
 * A control point placed on the map.
 *
 * For [HandleKind.Midpoint], [vertex] is the insertion path. [vertex] is null for a handle that
 * addresses no vertex, such as a rotation handle; [FeatureEditorState.moveVertex] does not apply to
 * it.
 */
public data class EditorHandle(val kind: HandleKind, val vertex: VertexRef?, val position: Position)

/** Shape a draft builds. */
@Serializable
public enum class DrawShape {
  Point,
  LineString,
  Polygon,
  Rectangle,
}

/**
 * Geometry under construction. [positions] are placed vertices; [cursor] is the mouse position
 * after them, or null. [cursor] is not serialized but takes part in equality.
 */
@Serializable
public data class EditorDraft(
  val shape: DrawShape,
  val positions: List<Position>,
  @Transient val cursor: Position? = null,
)
