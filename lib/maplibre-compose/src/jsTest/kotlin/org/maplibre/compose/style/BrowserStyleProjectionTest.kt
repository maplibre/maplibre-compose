package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BrowserStyleProjectionTest {
  @Test
  fun every_projection_form_writes_and_reads_back(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EMPTY_STYLE)
      val projection = fixture.state.style.projection
      assertNull(projection.getProperty("type"))

      projection.set(Projection(type = const(ProjectionType.Globe)))
      assertEquals(JsonPrimitive("globe"), projection.getProperty("type"))

      val transition =
        ProjectionTransition(ProjectionType.VerticalPerspective, ProjectionType.Mercator, 0.5f)
      projection.set(Projection(type = const(transition)))
      assertEquals(
        JsonArray(
          listOf(
            JsonPrimitive("vertical-perspective"),
            JsonPrimitive("mercator"),
            JsonPrimitive(0.5),
          )
        ),
        projection.getProperty("type"),
      )

      projection.set(
        Projection(
          type =
            interpolate(
              linear(),
              zoom(),
              10 to const(ProjectionType.VerticalPerspective),
              12 to const(ProjectionType.Mercator),
            )
        )
      )
      assertEquals(
        JsonPrimitive("interpolate"),
        (projection.getProperty("type") as JsonArray).first(),
      )

      projection.set(Projection())
      assertEquals(JsonPrimitive("mercator"), projection.getProperty("type"))
    }
  }

  @Test
  fun a_declared_projection_reads_back(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(GLOBE_STYLE)
      assertEquals(JsonPrimitive("globe"), fixture.state.style.projection.getProperty("type"))
    }
  }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val GLOBE_STYLE =
      BaseStyle.Json("""{"version":8,"projection":{"type":"globe"},"sources":{},"layers":[]}""")
  }
}
