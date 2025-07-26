package org.maplibre.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import io.github.kevincianfarini.alchemist.scalar.centimeters
import io.github.kevincianfarini.alchemist.scalar.kilometers
import io.github.kevincianfarini.alchemist.scalar.toLength
import io.github.kevincianfarini.alchemist.type.Length
import io.github.kevincianfarini.alchemist.unit.LengthUnit
import io.github.kevincianfarini.alchemist.unit.LengthUnit.International.Kilometer
import io.github.kevincianfarini.alchemist.unit.LengthUnit.International.Meter
import io.github.kevincianfarini.alchemist.unit.LengthUnit.UnitedStatesCustomary.Foot
import io.github.kevincianfarini.alchemist.unit.LengthUnit.UnitedStatesCustomary.Mile
import io.github.kevincianfarini.alchemist.unit.LengthUnit.UnitedStatesCustomary.Yard
import kotlin.math.pow
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.material3.generated.Res
import org.maplibre.compose.material3.generated.feet_symbol
import org.maplibre.compose.material3.generated.kilometers_symbol
import org.maplibre.compose.material3.generated.meters_symbol
import org.maplibre.compose.material3.generated.miles_symbol
import org.maplibre.compose.material3.generated.yards_symbol
import org.maplibre.compose.material3.util.rememberNumberFormatter

/** A measurement system to show in the scale bar. */
public interface ScaleBarMeasure {
  /**
   * List of stops, sorted ascending, at which the scalebar should show. For best results, each stop
   * should be no more than 2.5x times as big as the one before.
   */
  public val stops: List<Length>

  /** Get the formatted text to show for a given stop. */
  @Composable public fun getText(stop: Length): String

  /**
   * A scale bar measurement system that generates stops based on given units and m * 10^e.
   *
   * For example, if the units are [Meter, Kilometer] and the mantissas are [1, 2, 5], the stops
   * will be: 0.1m, 0.2m, 0.5m, 1m, 2m, 5m, 10m, 20m, 50m, 100m, 200m, 500m, 1km, 2km, 5km, 10km,
   * ...
   *
   * @param units the units to generate stops for.
   * @param mantissas the mantissas to generate stops for. Must be single digit positive integers.
   */
  public open class Default(
    units: Set<LengthUnit>,
    mantissas: Set<Int> = setOf(1, 2, 5),
    lowerBound: Length = 100.centimeters,
    upperBound: Length = 50000.kilometers,
  ) : ScaleBarMeasure {

    public constructor(vararg units: LengthUnit) : this(units.toSet())

    init {
      require(units.isNotEmpty()) { "At least one unit must be provided" }
      require(mantissas.isNotEmpty()) { "At least one mantissa must be provided" }
      require(mantissas.all { it in 1..<10 }) { "Mantissas must be single digit positive integers" }
    }

    private val sortedUnits = units.sortedBy { it.nanometerScale }
    private val sortedMantissas = mantissas.sorted()

    override val stops: List<Length> = buildList {
      // select the largest e that results in a stop below the lower bound
      val firstE =
        (0 downTo Int.MIN_VALUE).first { e ->
          sortedMantissas.first().toLength(sortedUnits.first()) * 10.0.pow(e) <= lowerBound
        }

      // generate stops, switching units when the next larger unit is reached
      val unboundedStops = sequence {
        sortedUnits.forEachIndexed { i, unit ->
          val unitStops = sequence {
            val e1 = if (i > 0) 0 else firstE
            for (e in e1..<Int.MAX_VALUE) {
              for (m in sortedMantissas) yield((m * 10.0.pow(e)).toLength(unit))
            }
          }

          val threshold = sortedUnits.getOrNull(i + 1)?.let { sortedMantissas.first().toLength(it) }
          unitStops.takeWhile { threshold == null || it < threshold }.forEach { yield(it) }
        }
      }

      // take stops until the upper bound
      unboundedStops.takeWhile { it <= upperBound }.forEach { add(it) }
    }

    @Composable
    final override fun getText(stop: Length): String {
      val unit = sortedUnits.lastOrNull { 1.toLength(it) <= stop } ?: sortedUnits.last()
      return getText(stop.toDouble(unit), unit)
    }

    /** Get the formatted text to show for a given generated stop and length. */
    @Composable
    protected open fun getText(stop: Double, unit: LengthUnit): String {
      val formatter = rememberNumberFormatter(Locale.current)
      return "${formatter.format(stop)}\u202F${unit.symbol}"
    }
  }

  public data object Metric : Default(Meter, Kilometer) {
    @Composable
    override fun getText(stop: Double, unit: LengthUnit): String {
      val formatter = rememberNumberFormatter(Locale.current)
      val symbol =
        when (unit) {
          Meter -> stringResource(Res.string.meters_symbol)
          Kilometer -> stringResource(Res.string.kilometers_symbol)
          else -> error("impossible")
        }
      return "${formatter.format(stop)}\u202F$symbol"
    }
  }

  public data object FeetAndMiles : Default(Foot, Mile) {
    @Composable
    override fun getText(stop: Double, unit: LengthUnit): String {
      val formatter = rememberNumberFormatter(Locale.current)
      val symbol =
        when (unit) {
          Foot -> stringResource(Res.string.feet_symbol)
          Mile -> stringResource(Res.string.miles_symbol)
          else -> error("impossible")
        }
      return "${formatter.format(stop)}\u202F$symbol"
    }
  }

  public data object YardsAndMiles : Default(Yard, Mile) {
    @Composable
    override fun getText(stop: Double, unit: LengthUnit): String {
      val formatter = rememberNumberFormatter(Locale.current)
      val symbol =
        when (unit) {
          Yard -> stringResource(Res.string.yards_symbol)
          Mile -> stringResource(Res.string.miles_symbol)
          else -> error("impossible")
        }
      return "${formatter.format(stop)}\u202F$symbol"
    }
  }
}
