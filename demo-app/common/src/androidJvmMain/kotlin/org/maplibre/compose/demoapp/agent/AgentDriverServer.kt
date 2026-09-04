package org.maplibre.compose.demoapp.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import co.touchlab.kermit.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.maplibre.compose.demoapp.DemoAppState

private val logger = Logger.withTag("AgentDriver")

private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_PORT = 8765

/** The platform the agent driver reports in `/health`. */
internal expect val agentPlatformName: String

/** A capture of the app window as PNG bytes, or null where screenshots are unsupported. */
@Composable internal expect fun rememberAgentScreenshotCapture(): (suspend () -> ByteArray)?

@Composable
actual fun StartAgentDriver(state: DemoAppState) {
  val density = LocalDensity.current
  val screenshotCapture = rememberAgentScreenshotCapture()
  val driver =
    remember(state, density, screenshotCapture) {
      AgentDriver(state, density, screenshotCapture, agentPlatformName)
    }
  DisposableEffect(driver) {
    val server =
      try {
        startAgentServer(driver)
      } catch (e: Exception) {
        logger.w(e) { "agent driver disabled: ${e.message}" }
        null
      }
    onDispose { server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000) }
  }
}

private fun startAgentServer(driver: AgentDriver): EmbeddedServer<*, *> {
  val port = System.getenv("MAPLIBRE_DEMO_AGENT_PORT")?.toIntOrNull() ?: DEFAULT_PORT
  val server =
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
      install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
          call.respondText(
            json.encodeToString(ErrorDto("unknown route; GET / returns the route index")),
            ContentType.Application.Json,
            status,
          )
        }
      }
      routing {
        get("/") { call.respondResult { driver.routeIndex() } }
        get("/health") { call.respondResult { driver.health() } }
        get("/demos") { call.respondResult { driver.demos() } }
        post("/demos/select") { call.respondResult { driver.selectDemo(call.jsonBody()) } }
        get("/camera") { call.respondResult { driver.camera() } }
        put("/camera") { call.respondResult { driver.jumpCamera(call.jsonBody()) } }
        post("/camera/animate") { call.respondResult { driver.animateCamera(call.jsonBody()) } }
        get("/style") { call.respondResult { driver.style() } }
        put("/style") { call.respondResult { driver.setStyle(call.jsonBody()) } }
        get("/state") { call.respondResult { driver.fullState() } }
        post("/wait/idle") {
          call.respondResult {
            val text = call.receiveText()
            driver.waitIdle(if (text.isBlank()) WaitIdleRequest() else decode(text))
          }
        }
        get("/features") {
          call.respondResult {
            val x = call.requiredQueryParam("x")
            val y = call.requiredQueryParam("y")
            val layerIds =
              call.request.queryParameters["layerIds"]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toSet()
            driver.featuresJson(x, y, layerIds)
          }
        }
        get("/screenshot") { call.respondResult { driver.screenshot() } }
        post("/gestures/pan") { call.respondResult { driver.pan(call.jsonBody()) } }
        post("/gestures/zoom") { call.respondResult { driver.zoom(call.jsonBody()) } }
      }
    }
  server.start(wait = false)
  logger.i { "agent driver listening on http://127.0.0.1:$port" }
  return server
}

private fun ApplicationCall.requiredQueryParam(name: String): Float {
  val raw =
    request.queryParameters[name]
      ?: throw AgentException(400, "query parameter '$name' (pixels) is required")
  return raw.toFloatOrNull()?.takeIf { it.isFinite() }
    ?: throw AgentException(400, "query parameter '$name' must be a number in pixels, got '$raw'")
}

private suspend inline fun <reified T> decode(text: String): T =
  try {
    json.decodeFromString<T>(text)
  } catch (e: Exception) {
    throw AgentException(400, "invalid JSON body: ${e.message}")
  }

private suspend inline fun <reified T> ApplicationCall.jsonBody(): T {
  val text = receiveText()
  if (text.isBlank()) throw AgentException(400, "a JSON request body is required")
  return decode(text)
}

private suspend fun ApplicationCall.respondError(e: AgentException) {
  respondText(
    json.encodeToString(ErrorDto(e.message ?: "error")),
    ContentType.Application.Json,
    HttpStatusCode.fromValue(e.statusCode),
  )
}

/**
 * Runs [block] on the main dispatcher and responds with its result: serialized JSON for DTOs, raw
 * JSON for a [String], PNG for a [ByteArray]. Every route maps [AgentException] to its status with
 * an [ErrorDto] body, and any other failure to a 500 [ErrorDto].
 */
private suspend inline fun <reified T> ApplicationCall.respondResult(
  crossinline block: suspend () -> T
) {
  try {
    when (val result = withContext(Dispatchers.Main) { block() }) {
      is ByteArray -> respondBytes(result, ContentType.Image.PNG)
      is String -> respondText(result, ContentType.Application.Json)
      else -> respondText(json.encodeToString(result), ContentType.Application.Json)
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: AgentException) {
    respondError(e)
  } catch (e: Exception) {
    logger.w(e) { "agent driver request failed" }
    respondText(
      json.encodeToString(ErrorDto(e.message ?: "internal error")),
      ContentType.Application.Json,
      HttpStatusCode.InternalServerError,
    )
  }
}
