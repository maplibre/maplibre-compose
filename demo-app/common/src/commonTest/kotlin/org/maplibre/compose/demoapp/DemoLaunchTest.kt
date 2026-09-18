package org.maplibre.compose.demoapp

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.demos.LiveTrackingDemo

class DemoLaunchTest {
  @Test
  fun no_arguments_launch_the_menu() {
    assertEquals(DemoLaunch.None, DemoLaunch.parse(emptyList()))
    assertEquals(DemoLaunch.None, DemoLaunch.parse(listOf("--route", "demos")))
  }

  @Test
  fun command_line_accepts_equals_and_separate_values() {
    val launch = DemoLaunch.parse(listOf("--route=demo/live-tracking", "--extent", "400x800"))
    assertEquals(DemoLaunchRoute.Demo(LiveTrackingDemo), launch.route)
    assertEquals(DpSize(400.dp, 800.dp), launch.windowSize)
  }

  @Test
  fun camera_follows_the_map_hash_order() {
    val camera = DemoLaunch.parse(listOf("--camera=12.5/47.6/-122.33/45/30")).camera!!
    assertEquals(12.5, camera.zoom)
    assertEquals(47.6, camera.target.latitude)
    assertEquals(-122.33, camera.target.longitude)
    assertEquals(45.0, camera.bearing)
    assertEquals(30.0, camera.tilt)
    val flat = DemoLaunch.parse(listOf("--camera=#9/40.7/-74")).camera!!
    assertEquals(0.0, flat.bearing)
    assertEquals(0.0, flat.tilt)
  }

  @Test
  fun routes_mirror_the_panel_routes() {
    assertNull(DemoLaunchRoute.parse("demos"))
    assertEquals(DemoLaunchRoute.Benchmarks, DemoLaunchRoute.parse("benchmarks"))
    assertEquals(
      DemoLaunchRoute.Benchmark(BenchmarkScenario.Animation),
      DemoLaunchRoute.parse("benchmark/animation"),
    )
    assertEquals(DemoLaunchRoute.Settings(null), DemoLaunchRoute.parse("settings"))
    assertEquals(DemoLaunchRoute.Settings("camera"), DemoLaunchRoute.parse("settings/camera"))
    assertIs<DemoLaunchRoute.Demo>(DemoLaunchRoute.parse("demo/3d-manhattan"))
  }

  @Test
  fun unknown_values_are_rejected() {
    assertFailsWith<IllegalArgumentException> { DemoLaunchRoute.parse("demo/nope") }
    assertFailsWith<IllegalArgumentException> { DemoLaunchRoute.parse("settings/nope") }
    assertFailsWith<IllegalArgumentException> { DemoLaunchRoute.parse("demo") }
    assertFailsWith<IllegalArgumentException> { DemoLaunch.parse(listOf("--camera=12/47.6")) }
    assertFailsWith<IllegalArgumentException> { DemoLaunch.parse(listOf("--extent=400")) }
  }
}
