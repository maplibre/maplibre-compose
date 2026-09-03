package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertNull
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class MlnFfiSkyAndProjectionTest {
  @Test
  fun sky_and_projection_writes_are_accepted_and_read_nothing_back(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sky":{"atmosphere-blend":0.5},"projection":{"type":"globe"},"sources":{},"layers":[]}"""
        )
      )
      val style = fixture.state.style
      style.sky.set(Sky())
      style.projection.set(Projection(type = const(ProjectionType.Globe)))
      assertNull(style.sky.getProperty("atmosphere-blend"))
      assertNull(style.projection.getProperty("type"))
    }
  }
}
