package org.maplibre.compose.util

import androidx.compose.ui.text.intl.Locale

private fun formatNumber(locale: String, maximumFractionDigits: Int, value: Double): String =
  js("new Intl.NumberFormat(locale, {maximumFractionDigits: maximumFractionDigits}).format(value)")

internal actual class NumberFormatter
actual constructor(locale: Locale, maximumFractionDigits: Int) {

  private val localeTag = locale.toLanguageTag()
  private val digits = maximumFractionDigits.coerceAtMost(100)

  actual fun format(value: Number): String = formatNumber(localeTag, digits, value.toDouble())
}
