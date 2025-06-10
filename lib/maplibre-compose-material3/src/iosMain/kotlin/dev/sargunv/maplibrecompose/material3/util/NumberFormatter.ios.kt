package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.intl.Locale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

private class NumberFormatterIos(locale: Locale, maximumFractionDigits: Int) : NumberFormatter {

  private val format =
    NSNumberFormatter().also {
      it.numberStyle = NSNumberFormatterDecimalStyle
      it.maximumFractionDigits = maximumFractionDigits.toULong()
      it.locale = locale.platformLocale
    }

  override fun format(value: Number): String =
    format.stringFromNumber(value as NSNumber) ?: value.toString()
}

internal actual fun NumberFormatter(locale: Locale, maximumFractionDigits: Int): NumberFormatter =
  NumberFormatterIos(locale, maximumFractionDigits)
