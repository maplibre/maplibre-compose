package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale
import java.text.NumberFormat

private class NumberFormatterDesktop(locale: Locale, maximumFractionDigits: Int) : NumberFormatter {

  private val format =
    NumberFormat.getInstance(locale.platformLocale).also {
      it.maximumFractionDigits = maximumFractionDigits
    }

  override fun format(value: Number): String = format.format(value)
}

internal actual fun NumberFormatter(locale: Locale, maximumFractionDigits: Int): NumberFormatter =
  NumberFormatterDesktop(locale, maximumFractionDigits)
