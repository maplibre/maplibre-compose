package org.maplibre.compose.expressions.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.expressions.value.ProjectionValue
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.style.ProjectionTransition

class EnumValueTest {
  @Test
  fun named_values_compile_to_style_strings() {
    val cap: Expression<LineCap> = const(LineCap.Round)
    assertEquals("\"round\"", styleJson(cap))
    assertEquals("\"viewport-glyph\"", styleJson(const(TextRotationAlignment.ViewportGlyph)))
  }

  @Test
  fun lists_keep_the_concrete_style_type() {
    val caps: Expression<ListValue<LineCap>> = const(LineCap.entries)
    assertEquals("""["literal",["butt","round","square"]]""", styleJson(caps))
  }

  @Test
  fun dynamic_inputs_select_typed_outputs_with_an_explicit_fallback() {
    val cap: Expression<LineCap> =
      switch(
        input = feature["cap"],
        case("round", const(LineCap.Round)),
        case("square", const(LineCap.Square)),
        fallback = const(LineCap.Butt),
      )
    assertEquals(
      """["match",["get","cap"],"round","round","square","square","butt"]""",
      styleJson(cap),
    )
  }

  @Test
  fun assertions_take_the_type_from_the_companion_and_check_fallbacks_in_order() {
    val cap: Expression<LineCap> =
      feature["cap"].asEnum(LineCap, feature["fallback"], const(LineCap.Round))
    val values = """["literal",["butt","round","square"]]"""
    assertEquals(
      """["string",["case",["in",["get","cap"],$values],["get","cap"],""" +
        """["in",["get","fallback"],$values],["get","fallback"],""" +
        """["in","round",$values],"round",null]]""",
      styleJson(cap),
    )
  }

  @Test
  fun projections_keep_their_string_and_transition_encodings() {
    val projection: Expression<ProjectionValue> = const(ProjectionType.VerticalPerspective)
    assertEquals("\"vertical-perspective\"", styleJson(projection))
    assertEquals(
      """["vertical-perspective","mercator",0.5]""",
      styleJson(
        const(
          ProjectionTransition(ProjectionType.VerticalPerspective, ProjectionType.Mercator, 0.5f)
        )
      ),
    )
  }
}
