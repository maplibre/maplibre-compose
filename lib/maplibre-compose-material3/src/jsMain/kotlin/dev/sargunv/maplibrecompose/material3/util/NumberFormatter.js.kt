package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale

private class NumberFormatterJs(locale: Locale, maximumFractionDigits: Int): NumberFormatter {

  val format = NumberFormat(
    locales = locale.toLanguageTag(),
    numberFormatOptions = NumberFormatOptions(maximumFractionDigits = maximumFractionDigits)
  )

  override fun format(value: Number): String =
    format.format(value)
}

internal actual fun NumberFormatter(locale: Locale, maximumFractionDigits: Int): NumberFormatter =
  NumberFormatterJs(locale, maximumFractionDigits)
