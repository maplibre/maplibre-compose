package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * Type of a GeoJson feature, as returned by
 * [Feature.geometryType][org.maplibre.compose.expressions.dsl.Feature.geometryType].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class GeometryType private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<GeometryType> {
    public val Point: GeometryType = GeometryType("Point")
    public val LineString: GeometryType = GeometryType("LineString")
    public val Polygon: GeometryType = GeometryType("Polygon")
    public val MultiPoint: GeometryType = GeometryType("MultiPoint")
    public val MultiLineString: GeometryType = GeometryType("MultiLineString")
    public val MultiPolygon: GeometryType = GeometryType("MultiPolygon")

    public override val entries: List<GeometryType> =
      listOf(Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon)
  }
}
