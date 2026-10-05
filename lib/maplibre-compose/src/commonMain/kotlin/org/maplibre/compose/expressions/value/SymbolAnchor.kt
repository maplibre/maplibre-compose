package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/** Part of the icon/text placed closest to the anchor. */
@JvmInline
public value class SymbolAnchor private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<SymbolAnchor> {
    /** The center of the icon is placed closest to the anchor. */
    public val Center: SymbolAnchor = SymbolAnchor("center")

    /** The left side of the icon is placed closest to the anchor. */
    public val Left: SymbolAnchor = SymbolAnchor("left")

    /** The right side of the icon is placed closest to the anchor. */
    public val Right: SymbolAnchor = SymbolAnchor("right")

    /** The top of the icon is placed closest to the anchor. */
    public val Top: SymbolAnchor = SymbolAnchor("top")

    /** The bottom of the icon is placed closest to the anchor. */
    public val Bottom: SymbolAnchor = SymbolAnchor("bottom")

    /** The top left corner of the icon is placed closest to the anchor. */
    public val TopLeft: SymbolAnchor = SymbolAnchor("top-left")

    /** The top right corner of the icon is placed closest to the anchor. */
    public val TopRight: SymbolAnchor = SymbolAnchor("top-right")

    /** The bottom left corner of the icon is placed closest to the anchor. */
    public val BottomLeft: SymbolAnchor = SymbolAnchor("bottom-left")

    /** The bottom right corner of the icon is placed closest to the anchor. */
    public val BottomRight: SymbolAnchor = SymbolAnchor("bottom-right")

    public override val entries: List<SymbolAnchor> =
      listOf(Center, Left, Right, Top, Bottom, TopLeft, TopRight, BottomLeft, BottomRight)
  }
}
