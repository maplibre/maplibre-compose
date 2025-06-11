package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  val format =
    NumberFormat(
      locales = locale.toLanguageTag(),
      numberFormatOptions = NumberFormatOptions(maximumFractionDigits = maximumFractionDigits),
    )

  actual fun format(value: Number): String = format.format(value)
}
