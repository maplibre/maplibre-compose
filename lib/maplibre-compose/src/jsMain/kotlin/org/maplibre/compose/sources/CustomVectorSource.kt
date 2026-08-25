package org.maplibre.compose.sources

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.gljs.RequestParameters
import org.maplibre.compose.gljs.addProtocol
import org.maplibre.compose.gljs.removeProtocol
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

public actual class CustomVectorSource : Source {
  private class SharedRequest(val work: Deferred<ProtocolResponse>) {
    var clients = 0
  }

  private class Attachment(val generation: Long, val protocol: String, val scope: CoroutineScope) {
    val requests = mutableMapOf<TileCoordinate, SharedRequest>()
  }

  private val options: CustomVectorSourceOptions
  private val provider: VectorTileProvider
  private var nextAttachment = 0L
  private var attachment: Attachment? = null

  public actual constructor(
    id: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ) : super(id) {
    this.options = options
    this.provider = provider
  }

  override fun addTo(binding: GlJsStyleBinding) {
    closeAttachment()
    val protocol = "maplibre-compose-custom-vector-${nextProtocolId++}"
    val current =
      Attachment(
        generation = ++nextAttachment,
        protocol = protocol,
        scope =
          CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-custom-vector-$id")
          ),
      )
    attachment = current
    addProtocol(protocol) { request, abortController ->
      loadProtocolTile(current, request, abortController)
    }
    try {
      binding.addSource(id, sourceJson(protocol))
    } catch (error: Throwable) {
      closeAttachment()
      throw error
    }
  }

  override fun detachedFromStyle() {
    closeAttachment()
  }

  override fun toJson(): JsonObject = sourceJson(attachment?.protocol ?: "unattached")

  private fun sourceJson(protocol: String): JsonObject = buildJsonObject {
    put("type", "vector")
    putJsonArray("tiles") { add("$protocol://tiles/{z}/{x}/{y}") }
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
  }

  private fun loadProtocolTile(
    current: Attachment,
    request: RequestParameters,
    abortController: Any,
  ): Promise<ProtocolResponse> {
    check(attachment?.generation == current.generation) { "Custom vector source '$id' is detached" }
    val tile = parseTileCoordinate(request.url)
    val shared =
      current.requests[tile]?.takeUnless { it.work.isCancelled }
        ?: SharedRequest(
            current.scope.async(start = CoroutineStart.LAZY) {
              val data = provider.loadTile(tile)
              currentCoroutineContext().ensureActive()
              if (attachment?.generation != current.generation) {
                throw CancellationException("Custom vector source was detached")
              }
              data.toProtocolResponse()
            }
          )
          .also { current.requests[tile] = it }
    val work =
      current.scope.async(start = CoroutineStart.LAZY) {
        shared.work.await()
      }
    shared.clients++

    val signal = abortController.asDynamic().signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion {
      shared.clients--
      if (shared.clients == 0 && current.requests[tile] === shared) {
        current.requests.remove(tile)
        if (!shared.work.isCompleted) shared.work.cancel()
      }
      signal.removeEventListener("abort", abort)
    }
    if (signal.aborted == true) work.cancel()
    work.start()
    return work.asPromise()
  }

  private fun closeAttachment() {
    val current = attachment ?: return
    attachment = null
    current.requests.clear()
    current.scope.cancel()
    removeProtocol(current.protocol)
  }

  public actual fun invalidateTile(tile: TileCoordinate) {
    throw UnsupportedOperationException(
      "CustomVectorSource.invalidateTile is not available in the browser because MapLibre GL JS " +
        "has no public per-tile invalidation operation."
    )
  }

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    val filter: FilterSpecification? =
      predicate
        .takeUnless { it == const(true) }
        ?.compile(ExpressionContext.None)
        ?.toStyleJson()
        ?.toJsValue()
    return binding
      ?.withMap { map ->
        sourceLayerIds.flatMap { layer ->
          val query =
            unsafeJso<QuerySourceFeatureOptions> {
              sourceLayer = layer
              this.filter = filter
            }
          map.querySourceFeatures(id, query).map { it.toGeoJsonFeature() }
        }
      }
      .orEmpty()
  }

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    setJsFeatureState(featureId = featureId, sourceLayerId = sourceLayerId, state = state)
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    jsFeatureState(featureId, sourceLayerId)

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    removeJsFeatureState(featureId = featureId, sourceLayerId = sourceLayerId, stateKey = stateKey)
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    removeJsFeatureState(sourceLayerId = sourceLayerId)
  }

  private companion object {
    var nextProtocolId = 1L
  }
}

private fun parseTileCoordinate(url: String): TileCoordinate {
  val components = url.substringBefore('?').trimEnd('/').split('/')
  require(components.size >= 3) { "Invalid custom vector tile URL: $url" }
  val coordinate = components.takeLast(3)
  return TileCoordinate(
    zoomLevel = coordinate[0].toInt(),
    x = coordinate[1].toLong(),
    y = coordinate[2].toLong(),
  )
}

private fun ByteArray.toProtocolResponse(): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
  return unsafeJso { data = bytes.buffer }
}
