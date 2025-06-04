package dev.sargunv.maplibrecompose.material3.style

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.layer.BackgroundLayer
import dev.sargunv.maplibrecompose.compose.layer.FillLayer
import dev.sargunv.maplibrecompose.compose.layer.LineLayer
import dev.sargunv.maplibrecompose.compose.source.rememberVectorSource
import dev.sargunv.maplibrecompose.core.source.Source
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.case
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.switch

@Composable
public fun MaterialMapStyle(
  colorScheme: MapColorScheme,
  source: Source = rememberVectorSource("omt", "https://tiles.openfreemap.org/planet"),
) {
  BackgroundLayer(id = "land", color = const(colorScheme.land))
  FillLayer(id = "water", source = source, sourceLayer = "water", color = const(colorScheme.water))
  LineLayer(
    id = "streets",
    source = source,
    sourceLayer = "transportation",
    color =
      switch(
        feature.get("class").asString(),
        case("motorway", const(colorScheme.motorway)),
        case("trunk", const(colorScheme.trunk)),
        case("primary", const(colorScheme.primary)),
        case("secondary", const(colorScheme.secondary)),
        case("tertiary", const(colorScheme.tertiary)),
        case("minor", const(colorScheme.minor)),
        fallback = const(Color.Transparent),
      ),
    width = const(4.dp),
  )
}

@Immutable
public data class MapColorScheme(
  val land: Color,
  val water: Color,
  val motorway: Color,
  val trunk: Color,
  val primary: Color,
  val secondary: Color,
  val tertiary: Color,
  val minor: Color,
) {
  public constructor(
    colorScheme: ColorScheme
  ) : this(
    land = colorScheme.surface,
    water = colorScheme.surfaceContainerLowest,
    motorway = colorScheme.primary,
    trunk = colorScheme.primary,
    primary = colorScheme.secondary,
    secondary = colorScheme.secondary,
    tertiary = colorScheme.tertiary,
    minor = colorScheme.tertiary,
  )
}
