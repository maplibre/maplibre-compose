package org.maplibre.compose.util

import androidx.compose.ui.text.intl.Locale
import js.intl.NumberFormat
import js.intl.NumberFormatOptions

private fun numberFormatOptions(digits: Int): NumberFormatOptions =
  js("({ maximumFractionDigits: digits })")

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val numberFormat =
    NumberFormat(
      locales = locale.toLanguageTag(),
      // Preserve support for Safari versions whose Intl limit is 20.
      options = numberFormatOptions(maximumFractionDigits.coerceAtMost(20)),
    )

  actual fun format(value: Number): String = numberFormat.format(value.toDouble())
}
