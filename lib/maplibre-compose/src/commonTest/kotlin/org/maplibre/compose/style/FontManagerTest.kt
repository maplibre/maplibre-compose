package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.ast.FontLiteral

class FontManagerTest {

  @Test
  fun references_to_the_same_file_share_one_registration() {
    val manager = FontManager(StyleNode(RecordingStyleBinding()))
    val first = FontLiteral.of("Body", byteArrayOf(1, 2, 3), emptyList())
    val second = FontLiteral.of("Body", byteArrayOf(1, 2, 3), listOf("Fallback"))

    assertEquals(listOf("Body"), manager.acquire(first))
    assertEquals(listOf("Body", "Fallback"), manager.acquire(second))
    assertEquals(1, manager.desiredFonts.size)

    manager.release(first)
    assertEquals(1, manager.desiredFonts.size)
    manager.release(second)
    assertTrue(manager.desiredFonts.isEmpty())
  }

  @Test
  fun the_latest_file_registered_under_a_name_is_the_one_declared() {
    val manager = FontManager(StyleNode(RecordingStyleBinding()))
    val old = FontLiteral.of("Body", byteArrayOf(1), emptyList())
    val new = FontLiteral.of("Body", byteArrayOf(2), emptyList())

    manager.acquire(old)
    manager.acquire(new)
    assertEquals(listOf(StyleFontDefinition("Body", new.file)), manager.desiredFonts)

    manager.release(new)
    assertEquals(listOf(StyleFontDefinition("Body", old.file)), manager.desiredFonts)
  }

  @Test
  fun acquiring_an_already_held_file_again_makes_it_the_declared_one() {
    val manager = FontManager(StyleNode(RecordingStyleBinding()))
    val first = FontLiteral.of("Body", byteArrayOf(1), emptyList())
    val second = FontLiteral.of("Body", byteArrayOf(2), emptyList())

    manager.acquire(first)
    manager.acquire(second)
    manager.acquire(first)
    assertEquals(listOf(StyleFontDefinition("Body", first.file)), manager.desiredFonts)

    manager.release(first)
    assertEquals(listOf(StyleFontDefinition("Body", first.file)), manager.desiredFonts)
    manager.release(first)
    assertEquals(listOf(StyleFontDefinition("Body", second.file)), manager.desiredFonts)
  }

  @Test
  fun a_literal_keeps_its_own_copy_of_the_bytes() {
    val bytes = byteArrayOf(1, 2, 3)
    val literal = FontLiteral.of("Body", bytes, emptyList())
    bytes[0] = 9
    literal.value[1] = 9

    assertEquals(FontFile(byteArrayOf(1, 2, 3)), literal.file)
    assertTrue(literal.file.bytes.contentEquals(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun a_font_file_is_identified_by_its_content() {
    val bytes = byteArrayOf(1, 2, 3)
    assertEquals(FontFile(bytes), FontFile(bytes.copyOf()))
    assertEquals(FontFile(bytes).url, FontFile(bytes.copyOf()).url)
    assertTrue(FontFile(bytes) != FontFile(byteArrayOf(1, 2, 4)))
    assertTrue(FontFile(bytes) != FontFile(byteArrayOf(1, 2, 3, 0)))
  }
}
