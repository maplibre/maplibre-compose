package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/** Frame of reference for offsetting geometry. */
@JvmInline
public value class TranslateAnchor private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<TranslateAnchor> {
    /** Offset is relative to the map */
    public val Map: TranslateAnchor = TranslateAnchor("map")

    /** Offset is relative to the viewport */
    public val Viewport: TranslateAnchor = TranslateAnchor("viewport")

    public override val entries: List<TranslateAnchor> = listOf(Map, Viewport)
  }
}
