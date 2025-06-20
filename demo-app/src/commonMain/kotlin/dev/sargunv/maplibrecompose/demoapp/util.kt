package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastRoundToInt
import dev.jordond.compass.geolocation.Geolocator
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import dev.sargunv.maplibrecompose.core.RenderOptions
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import io.github.dellisd.spatialk.geojson.Position
import kotlin.math.pow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.ExperimentalResourceApi

interface Demo {
  val name: String
  val description: String

  @Composable fun Component(navigateUp: () -> Unit)
}

data class StyleInfo(val name: String, val style: BaseStyle, val isDark: Boolean)

@OptIn(ExperimentalResourceApi::class)
val ALL_STYLES =
  listOf(
    StyleInfo(
      "Bright",
      BaseStyle.Uri("https://tiles.openfreemap.org/styles/bright"),
      isDark = false,
    ),
    StyleInfo(
      "Liberty",
      BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
      isDark = false,
    ),
    StyleInfo(
      "Positron",
      BaseStyle.Uri("https://tiles.openfreemap.org/styles/positron"),
      isDark = false,
    ),
    StyleInfo("Fiord", BaseStyle.Uri("https://tiles.openfreemap.org/styles/fiord"), isDark = true),
    StyleInfo("Dark", BaseStyle.Uri("https://tiles.openfreemap.org/styles/dark"), isDark = true),
    StyleInfo("Colorful", BaseStyle.Uri(Res.getUri("files/styles/colorful.json")), isDark = false),
    StyleInfo("Eclipse", BaseStyle.Uri(Res.getUri("files/styles/eclipse.json")), isDark = true),
    StyleInfo(
      "Graybeard",
      BaseStyle.Uri(Res.getUri("files/styles/graybeard.json")),
      isDark = false,
    ),
    StyleInfo("Neutrino", BaseStyle.Uri(Res.getUri("files/styles/neutrino.json")), isDark = true),
    StyleInfo(
      "OSM Carto",
      BaseStyle.Uri(Res.getUri("files/styles/osm-raster.json")),
      isDark = false,
    ),
  )

val DEFAULT_STYLE = ALL_STYLES[0].style
val MINIMAL_STYLE = ALL_STYLES[2].style

/** Caution: this converter results in a loss of precision far from the origin. */
class PositionVectorConverter(private val origin: Position) :
  TwoWayConverter<Position, AnimationVector2D> {
  override val convertFromVector: (AnimationVector2D) -> Position = { vector ->
    Position(
      longitude = vector.v1.toDouble() + origin.longitude,
      latitude = vector.v2.toDouble() + origin.latitude,
    )
  }

  override val convertToVector: (Position) -> AnimationVector2D = { pos ->
    val dLon = pos.longitude - origin.longitude
    val dLat = pos.latitude - origin.latitude
    AnimationVector2D(dLon.toFloat(), dLat.toFloat())
  }
}

internal fun Double.format(decimals: Int): String {
  val factor = 10.0.pow(decimals)
  return ((this * factor).fastRoundToInt() / factor).toString()
}

internal class FrameRateState(private val spinner: String = "◐◓◑◒") {
  private var rollingAverage by mutableStateOf(0.0)
  private var spinnerIndex by mutableStateOf(0)

  fun recordFps(framesPerSecond: Double) {
    rollingAverage = (rollingAverage * 0.9) + (framesPerSecond * 0.1)
    spinnerIndex = (spinnerIndex + 1) % spinner.length
  }

  val spinChar: Char
    get() = spinner[spinnerIndex]

  val avgFps: Int
    get() = rollingAverage.roundToInt()
}

@Composable expect fun getDefaultColorScheme(isDark: Boolean = false): ColorScheme

expect object Platform {
  val isAndroid: Boolean
  val isIos: Boolean
  val isDesktop: Boolean
  val isWeb: Boolean
}

val Platform.supportsStyling: Boolean
  get() = isAndroid || isIos

val Platform.supportsBlending: Boolean
  get() = isAndroid || isIos

val Platform.usesMaplibreNative: Boolean
  get() = isAndroid || isIos

expect fun getGeolocator(): Geolocator

expect fun RenderOptions.withMaxFps(maxFps: Int): RenderOptions

expect fun OrnamentOptions.withPadding(padding: PaddingValues): OrnamentOptions
