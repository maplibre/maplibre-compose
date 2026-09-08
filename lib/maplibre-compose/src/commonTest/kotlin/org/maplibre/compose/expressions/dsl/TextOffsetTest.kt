package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.compose.expressions.ast.ExpressionContext

class TextOffsetTest {
  @Test
  fun text_unit_overloads_keep_existing_offset_semantics() {
    assertEquals(offset(1.sp, (-2).sp), textOffset(1.sp, (-2).sp))
    assertEquals(offset(1.em, (-2).em), textOffset(1.em, (-2).em))
    assertFailsWith<IllegalArgumentException> { textOffset(1.sp, 1.em) }
  }

  @Test
  fun dp_offsets_require_specified_values_and_a_text_compilation_context() {
    assertFailsWith<IllegalArgumentException> { textOffset(Dp.Unspecified, 1.dp) }
    assertFailsWith<IllegalArgumentException> { textOffset(1.dp, Dp.Unspecified) }
    assertFailsWith<IllegalStateException> {
      textOffset(0.dp, 12.dp).compile(ExpressionContext.None)
    }
  }
}
