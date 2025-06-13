package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.TextUnit
import be.digitalia.compose.htmlconverter.HtmlStyle

internal actual fun htmlToAnnotatedString(html: String, textLinkStyles: TextLinkStyles?) =
  be.digitalia.compose.htmlconverter.htmlToAnnotatedString(
    html,
    compactMode = true,
    style = HtmlStyle(indentUnit = TextUnit.Unspecified, textLinkStyles = textLinkStyles),
  )
