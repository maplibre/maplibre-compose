package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.expressions.value.ProjectionValue

/**
 * The style's map projection.
 *
 * The default matches the style spec's `projection` object. The expression may use the zoom level,
 * so a map can be a globe when zoomed out and a flat map when zoomed in:
 * ```kt
 * Projection(
 *   type =
 *     interpolate(
 *       linear(),
 *       zoom(),
 *       10 to const(ProjectionType.VerticalPerspective),
 *       12 to const(ProjectionType.Mercator),
 *     )
 * )
 * ```
 *
 * MapLibre Native draws only the Mercator projection.
 *
 * @param type The projection to draw the map with: a [ProjectionType], a [ProjectionTransition], or
 *   an expression that resolves to one of them.
 */
@Immutable
public data class Projection(
  val type: Expression<ProjectionValue> = const(ProjectionType.Mercator)
) {
  internal fun toJson(): JsonObject = buildJsonObject { putExpression("type", type) }
}

/**
 * A projection part way between two others, such as the state of a globe as it flattens into a map.
 *
 * @param from The projection at a [progress] of 0.
 * @param to The projection at a [progress] of 1.
 * @param progress How far the projection has moved from [from] to [to]. A value in the range of
 *   `[0..1]`.
 */
@Immutable
public data class ProjectionTransition(
  val from: ProjectionType,
  val to: ProjectionType,
  val progress: Float,
) {
  init {
    require(progress in 0f..1f) { "Projection transition progress must be in [0..1]: $progress" }
  }
}
