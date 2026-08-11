package org.maplibre.compose.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.maplibre_logo
import org.maplibre.compose.generated.maplibre_logo_description
import org.maplibre.compose.util.tryOpenUri

/** The address that [MaplibreLogo] opens when it is clicked. */
public const val MaplibreWebsiteUrl: String = "https://maplibre.org/"

/**
 * The MapLibre wordmark, drawn at its intrinsic size of 88x23 dp.
 *
 * The artwork carries its own drop shadow, so it reads over both light and dark basemaps without a
 * container behind it.
 *
 * @param contentDescription Accessibility label for the logo.
 * @param onClick Called when the logo is clicked. Opens [MaplibreWebsiteUrl] by default. Pass
 *   `null` to draw the logo without making it clickable.
 */
@Composable
public fun MaplibreLogo(
  modifier: Modifier = Modifier,
  contentDescription: String? = stringResource(Res.string.maplibre_logo_description),
  onClick: (() -> Unit)? = rememberOpenWebsite(),
) {
  Image(
    painter = painterResource(Res.drawable.maplibre_logo),
    contentDescription = contentDescription,
    modifier =
      modifier.then(
        onClick?.let { Modifier.clickable(onClick = it, role = Role.Image) } ?: Modifier
      ),
  )
}

@Composable
internal fun rememberOpenWebsite(): () -> Unit {
  val uriHandler = LocalUriHandler.current
  return remember(uriHandler) { { uriHandler.tryOpenUri(MaplibreWebsiteUrl) } }
}
