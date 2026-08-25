package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.spatialk.geojson.BoundingBox

// The web platform has no offline API, so the section is empty.
@Composable
actual fun OfflineRegionSection(region: BoundingBox, styleUrl: String, packName: String) {}
