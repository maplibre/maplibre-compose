package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatterTest {
  val en = Locale("en-US")
  val fr = Locale("fr-FR")
  val de = Locale("de-DE")
  val ar = Locale("ar-SA")

  private fun formatter(locale: Locale) =
    NumberFormatter(locale = locale, maximumFractionDigits = Int.MAX_VALUE)

  @Test
  fun format() {
    assertEquals("1", formatter(en).format(1.0))
    assertEquals("1.5", formatter(en).format(1.5))
    assertEquals("1,5", formatter(fr).format(1.5))
    assertEquals("١٫٥", formatter(ar).format(1.5))

    assertEquals("1,000,000", formatter(en).format(1_000_000))
    // oh well, implementations may differ on what kind of whitespace is used
    // assertEquals("1 000 000", formatter(fr).format(1_000_000))
    assertEquals("1.000.000", formatter(de).format(1_000_000))
    assertEquals("١٬٠٠٠٬٠٠٠", formatter(ar).format(1_000_000))
  }
}
