package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * A named map projection. See [Projection][org.maplibre.compose.style.Projection].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class ProjectionType private constructor(override val value: String) :
  EnumValue, ProjectionValue {
  public companion object : EnumType<ProjectionType> {
    /** The Web Mercator projection. */
    public val Mercator: ProjectionType = ProjectionType("mercator")

    /** A globe projection at every zoom level. */
    public val VerticalPerspective: ProjectionType = ProjectionType("vertical-perspective")

    /** [VerticalPerspective] below zoom 11, interpolating to [Mercator] by zoom 12. */
    public val Globe: ProjectionType = ProjectionType("globe")

    public override val entries: List<ProjectionType> = listOf(Mercator, VerticalPerspective, Globe)
  }
}
