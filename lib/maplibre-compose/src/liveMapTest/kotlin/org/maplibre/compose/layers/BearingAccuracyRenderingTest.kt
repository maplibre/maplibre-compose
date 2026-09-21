package org.maplibre.compose.layers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class BearingAccuracyRenderingTest {
  @Test
  fun sector_shape_zoom_style_and_hit_testing_match_on_both_engines() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"black"}}]}"""
        )
      )
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 0.0))
      val layer = LocationIndicatorLayer("sector")
      val context = ExpressionContext.None
      layer.setLocation(Position(0.0, 0.0))
      layer.setBearing((const(0f).compile(context)).asLayerProperty())
      layer.setBearingAccuracy((const(15f).compile(context)).asLayerProperty())
      layer.setBearingAccuracyRadius(
        (interpolate(linear(), zoom(), 0 to const(80.dp), 2 to const(120.dp)).compile(context))
          .asLayerProperty()
      )
      layer.setBearingAccuracyColor(
        (interpolate(linear(), zoom(), 0 to const(Color.Red), 2 to const(Color.Blue))
            .compile(context))
          .asLayerProperty()
      )
      layer.setBearingTransition(TransitionOptions(Duration.ZERO))
      layer.setBearingAccuracyTransition(TransitionOptions(Duration.ZERO))
      layer.setBearingAccuracyRadiusTransition(TransitionOptions(Duration.ZERO))
      layer.setBearingAccuracyColorTransition(TransitionOptions(Duration.ZERO))
      val handle = checkNotNull(fixture.style).install(layer)
      fixture.pumpUntil("north-pointing sector without any images") {
        fixture.readPixel(256, 236).red > 180
      }
      val near = fixture.readPixel(256, 236)
      val far = fixture.readPixel(256, 196)
      assertTrue(near.red > far.red && far.red > 15, "sector fades outward: $near, $far")
      assertTrue(fixture.readPixel(276, 216).red < 5, "15 is a half-angle, not a full circle")
      assertTrue(fixture.readPixel(256, 276).red < 5, "nothing behind the bearing")
      assertTrue(
        fixture.state.queryRenderedFeatures(DpOffset(256.dp, 236.dp), setOf("sector")).isEmpty(),
        "sector pixels are not hit targets",
      )
      layer.setBearing((const(90f).compile(context)).asLayerProperty())
      handle.update(layer.definition())
      fixture.pumpUntil("east-pointing sector") { fixture.readPixel(276, 256).red > 180 }
      assertTrue(fixture.readPixel(256, 236).red < 5)
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 2.0))
      fixture.pumpUntil("zoom expression changes radius and color") {
        fixture.readPixel(346, 256).blue > 20
      }
      layer.setBearingAccuracy((const(180f).compile(context)).asLayerProperty())
      handle.update(layer.definition())
      fixture.pumpUntil("full circle") { fixture.readPixel(236, 256).blue > 180 }
      layer.setBearingAccuracy((const(0f).compile(context)).asLayerProperty())
      handle.update(layer.definition())
      fixture.pumpUntil("zero error hides sector") { fixture.readPixel(276, 256).blue < 5 }
      layer.setBearingAccuracy((const(15f).compile(context)).asLayerProperty())
      layer.setBearingAccuracyRadius((const(0.dp).compile(context)).asLayerProperty())
      handle.update(layer.definition())
      fixture.pump(2)
      assertTrue(fixture.readPixel(276, 256).blue < 5, "zero radius hides sector")
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }
}
