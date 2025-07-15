package org.maplibre.compose.material3.util

import androidx.compose.ui.text.intl.Locale
import js.intl.NumberFormat
import js.intl.NumberFormatOptions

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val format =
    NumberFormat(
      locales = locale.toLanguageTag(),
      options =
        NumberFormatOptions(
          // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/NumberFormat/NumberFormat#maximumfractiondigits
          maximumFractionDigits = maximumFractionDigits.coerceAtMost(100)
        ),
    )

  actual fun format(value: Number): String = format.format(value)
}
