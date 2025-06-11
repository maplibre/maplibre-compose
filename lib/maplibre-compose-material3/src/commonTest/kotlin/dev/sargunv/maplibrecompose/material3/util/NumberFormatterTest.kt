package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatterTest {
  val en = Locale("en-US")
  val fr = Locale("fr-FR")
  val de = Locale("de-DE")
  val ar = Locale("ar-SA")

  @Test
  fun format() {
    assertEquals("1", NumberFormatter(en).format(1.0))
    assertEquals("1.5", NumberFormatter(en).format(1.5))
    assertEquals("1,5", NumberFormatter(fr).format(1.5))
    assertEquals("١٫٥", NumberFormatter(ar).format(1.5))

    assertEquals("1,000,000", NumberFormatter(en).format(1_000_000))
    // oh well, implementations may differ on what kind of whitespace is used
    // assertEquals("1 000 000", NumberFormatter(fr).format(1_000_000))
    assertEquals("1.000.000", NumberFormatter(de).format(1_000_000))
    assertEquals("١٬٠٠٠٬٠٠٠", NumberFormatter(ar).format(1_000_000))
  }
}
