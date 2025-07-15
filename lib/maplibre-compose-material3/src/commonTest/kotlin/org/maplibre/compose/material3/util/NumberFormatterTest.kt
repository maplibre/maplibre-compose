package org.maplibre.compose.material3.util

import androidx.compose.ui.text.intl.Locale
import kotlin.test.Test
import kotlin.test.assertContains
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
    assertEquals("1.000.000", formatter(de).format(1_000_000))
    assertEquals("١٬٠٠٠٬٠٠٠", formatter(ar).format(1_000_000))

    // Older Android versions used NBSP; other platforms and newer Android use NNBSP
    assertContains(
      setOf("1\u202F000\u202F000", "1\u00A0000\u00A0000"),
      formatter(fr).format(1_000_000),
    )
  }
}
