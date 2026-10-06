package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.QuickZoomDirection

/**
 * The quick zoom direction of the platform's usual map app: Apple Maps on iOS, Google Maps
 * elsewhere.
 */
internal expect val platformQuickZoomDirection: QuickZoomDirection
