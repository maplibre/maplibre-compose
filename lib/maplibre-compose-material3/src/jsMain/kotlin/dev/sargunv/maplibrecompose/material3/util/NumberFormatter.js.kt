package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale
import js.intl.NumberFormat
import js.intl.NumberFormatOptions

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val format =
    NumberFormat(
      locales = locale.toLanguageTag(),
      options = NumberFormatOptions(maximumFractionDigits = maximumFractionDigits.coerceAtMost(20)),
    )

  actual fun format(value: Number): String = format.format(value)
}
