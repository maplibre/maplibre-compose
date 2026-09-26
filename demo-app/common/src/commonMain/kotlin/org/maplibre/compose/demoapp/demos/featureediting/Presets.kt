package org.maplibre.compose.demoapp.demos.featureediting

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.transformation.circle
import org.maplibre.spatialk.units.extensions.meters

/** Shapes in Golden Gate Park, San Francisco, traced from OpenStreetMap. */
internal object Presets {
  /** The park outline with the Panhandle attached along Stanyan Street. */
  val goldenGatePark: Feature<Geometry, JsonObject> =
    Feature(
      geometry =
        Polygon(
          listOf(
            listOf(
              Position(-122.51087, 37.77125),
              Position(-122.51023, 37.76415),
              Position(-122.45858, 37.76638),
              Position(-122.45696, 37.76597),
              Position(-122.45399, 37.76635),
              Position(-122.45318, 37.76664),
              Position(-122.45408, 37.77108),
              Position(-122.45265, 37.77122),
              Position(-122.44090, 37.77279),
              Position(-122.44105, 37.77354),
              Position(-122.45236, 37.77210),
              Position(-122.45426, 37.77196),
              Position(-122.45481, 37.77465),
              Position(-122.46529, 37.77335),
              Position(-122.51087, 37.77125),
            )
          )
        ),
      properties = buildJsonObject { put("name", "Golden Gate Park") },
      id = JsonPrimitive("golden-gate-park"),
    )

  /** John F. Kennedy Drive from the Great Highway east to the Stanyan Street entrance. */
  val jfkDrive: Feature<Geometry, JsonObject> =
    Feature(
      geometry =
        LineString(
          Position(-122.51009, 37.77028),
          Position(-122.50918, 37.76985),
          Position(-122.50853, 37.76942),
          Position(-122.50809, 37.76900),
          Position(-122.50749, 37.76827),
          Position(-122.50673, 37.76704),
          Position(-122.50631, 37.76668),
          Position(-122.50268, 37.76749),
          Position(-122.49847, 37.76879),
          Position(-122.49648, 37.76925),
          Position(-122.49580, 37.76949),
          Position(-122.49523, 37.76999),
          Position(-122.49491, 37.77017),
          Position(-122.49370, 37.77029),
          Position(-122.49232, 37.77072),
          Position(-122.49094, 37.77096),
          Position(-122.48869, 37.77077),
          Position(-122.48700, 37.77087),
          Position(-122.48654, 37.77073),
          Position(-122.48533, 37.77012),
          Position(-122.48486, 37.76999),
          Position(-122.48347, 37.77021),
          Position(-122.48232, 37.77003),
          Position(-122.48171, 37.77003),
          Position(-122.48043, 37.77032),
          Position(-122.47764, 37.77136),
          Position(-122.47585, 37.77170),
          Position(-122.47506, 37.77166),
          Position(-122.47274, 37.77083),
          Position(-122.47191, 37.77072),
          Position(-122.47108, 37.77104),
          Position(-122.46950, 37.77209),
          Position(-122.46891, 37.77236),
          Position(-122.46785, 37.77253),
          Position(-122.46599, 37.77254),
          Position(-122.46588, 37.77258),
          Position(-122.46584, 37.77268),
          Position(-122.46453, 37.77243),
          Position(-122.46192, 37.77175),
          Position(-122.45667, 37.77097),
          Position(-122.45546, 37.77096),
        ),
      properties = buildJsonObject { put("name", "JFK Drive") },
      id = JsonPrimitive("jfk-drive"),
    )

  /** A 400 m point buffer around the Conservatory of Flowers. */
  val conservatoryCircle: Feature<Geometry, JsonObject> =
    Feature(
      geometry = circle(Position(-122.4602, 37.7726), 400.meters, steps = 64),
      properties =
        buildJsonObject {
          put("name", "400 m around the Conservatory")
        },
      id = JsonPrimitive("conservatory-400m"),
    )
}
