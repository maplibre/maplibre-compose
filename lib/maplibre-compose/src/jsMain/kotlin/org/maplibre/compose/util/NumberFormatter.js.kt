package org.maplibre.compose.util

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
          // Safari raised this limit from 20 to 100 in Safari Technology Preview 178.
          // https://developer.apple.com/documentation/safari-technology-preview-release-notes/stp-release-178
          maximumFractionDigits = maximumFractionDigits.coerceAtMost(20)
        ),
    )

  actual fun format(value: Number): String = format.format(value)
}
