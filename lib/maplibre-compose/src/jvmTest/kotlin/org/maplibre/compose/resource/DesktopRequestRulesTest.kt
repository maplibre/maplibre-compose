package org.maplibre.compose.resource

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.runMapTest

class DesktopRequestRulesTest {

  /** A request as the server received it. */
  private data class Received(val pathAndQuery: String, val header: String?)

  @Test
  fun native_sends_the_url_and_headers_that_the_rules_produce() = runMapTest {
    val received = RecordingList<Received>()
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
      received +=
        Received(
          exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery,
          exchange.requestHeaders.getFirst(HeaderName),
        )
      val body = EmptyStyle.encodeToByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      val origin = "http://127.0.0.1:${server.address.port}"
      val styleUrl = "$origin/style.json?v=1"
      val rules = MapRequestRules {
        // Checked first, but covers only tiles.
        addQueryParameters(
          "http://127.0.0.1:*/**",
          mapOf("wrong" to "1"),
          setOf(MapResourceKind.Tile),
        )
        addQueryParameters("http://127.0.0.1:*/style.json?*", mapOf("key" to "a b", "n" to "ü"))
        // Header rules see the URL after the query parameter rules add to it.
        addHeaders("http://127.0.0.1:*/style.json?v=1&key=*", mapOf(HeaderName to "yes"))
      }
      val fixture = BridgeMapFixture.create(resourceConfig = MapResourceConfig(interceptor = rules))
      fixture.use {
        it.session.setBaseStyle(BaseStyle.Uri(styleUrl))
        it.awaitUntil("the style request to reach the server") { received.isNotEmpty() }
      }

      val expected =
        assertNotNull(rules.rewriteUrl(MapResourceRequest(styleUrl, MapResourceKind.Style)))
      assertEquals("$origin/style.json?v=1&key=a%20b&n=%C3%BC", expected)
      assertEquals(Received(expected.removePrefix(origin), "yes"), received.first())
    } finally {
      server.stop(0)
    }
  }

  private companion object {
    const val HeaderName = "X-Rule-Test"
    const val EmptyStyle = """{"version":8,"sources":{},"layers":[]}"""
  }
}
