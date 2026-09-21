package org.maplibre.compose.style

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Sole owner of preparation and resolved properties for one style composition. */
internal class StyleCompositionOwner(
  private val preparePainter: suspend (StyleImageRequest.Painter) -> StyleImageContent = {
    it.prepare()
  }
) {
  suspend fun run(
    declarations: ReceiveChannel<StyleDeclaration>,
    publish: suspend (DesiredStyleRevision) -> Unit,
  ): Unit = coroutineScope {
    val completions = Channel<Completion>(Channel.UNLIMITED)
    val entries = mutableMapOf<StyleImageRequest, Entry>()
    val ids = IncrementingId("image")
    var desired: StyleDeclaration? = null
    var previous = DesiredStyleRevision.Empty
    var properties = emptyMap<Pair<Any, StyleProperty>, ResolvedProperty>()
    try {
      while (true) {
        val changed =
          select<Boolean> {
            declarations.onReceive { declaration ->
              desired = declaration
              val required =
                declaration.layers
                  .flatMap { it.imageProperties.values }
                  .flatMap { it.images }
                  .toSet()
              entries.keys.filter { it !in required }.forEach { entries.remove(it)?.job?.cancel() }
              required.forEach { request ->
                if (request !in entries) {
                  val entry = Entry(request)
                  entries[request] = entry
                  when (request) {
                    is StyleImageRequest.Bitmap -> entry.content = request.prepare()
                    is StyleImageRequest.Painter -> entry.job = launch {
                        val result =
                          try {
                            Result.success(preparePainter(request))
                          } catch (error: CancellationException) {
                            throw error
                          } catch (error: Throwable) {
                            Result.failure(error)
                          }
                        completions.trySend(Completion(entry, result))
                      }
                  }
                }
              }
              true
            }
            completions.onReceive { completion ->
              if (entries[completion.entry.request] === completion.entry) {
                completion.entry.content = completion.result.getOrThrow()
                true
              } else false
            }
          }
        if (!changed) continue
        val declaration = desired ?: continue
        // Rebuild sharing from still-live values. There is no independent cache to prune.
        val images =
          previous.images
            .associateBy {
              StyleImageContent(it.image, it.sdf, it.stretch)
            }
            .toMutableMap()
        val resolved =
          entries
            .mapNotNull { (request, entry) ->
              entry.content?.let { content ->
                request to
                  images.getOrPut(content) {
                    StyleImageDefinition(ids.next(), content.image, content.sdf, content.stretch)
                  }
              }
            }
            .toMap()
        val resolvedIds = resolved.mapValues { it.value.id }
        val previousLayers =
          previous.layers.associateBy {
            it.registration ?: it.definition.id
          }
        val nextProperties = mutableMapOf<Pair<Any, StyleProperty>, ResolvedProperty>()
        val layers =
          declaration.layers.map { declared ->
            val layer = declared.layer
            val value = layer.definition.value.toMutableMap()
            declared.imageProperties.forEach { (path, property) ->
              val key = (layer.registration ?: layer.definition.id) to path
              val ready = property.images.all { it in resolved }
              val result =
                if (ready) {
                  ResolvedProperty(
                    property.resolve(resolvedIds),
                    property.images.map { resolved.getValue(it) },
                  )
                } else {
                  properties[key]
                    ?: previousLayers[key.first]?.definition?.value?.let { old ->
                      val container =
                        if (path.section == null) old else old[path.section] as? JsonObject
                      container?.get(path.name)?.let { ResolvedProperty(it, emptyList()) }
                    }
                }
              if (result != null) {
                nextProperties[key] = result
                if (path.section == null) value[path.name] = result.value
                else {
                  val section = (value[path.section] as? JsonObject).orEmpty().toMutableMap()
                  if (result.value != JsonNull) section[path.name] = result.value
                  value[path.section] = JsonObject(section)
                }
              }
            }
            layer.copy(definition = layer.definition.copy(value = JsonObject(value)))
          }
        properties = nextProperties
        val revision =
          DesiredStyleRevision(
            sources = declaration.sources,
            layers = layers,
            images = properties.values.flatMap { it.images }.distinctBy { it.id },
            animatorDurationScale = declaration.animatorDurationScale,
            fontScale = declaration.fontScale,
            imagesPending = entries.values.any { it.content == null },
          )
        previous = revision
        publish(revision)
      }
    } finally {
      entries.values.forEach { it.job?.cancel() }
      completions.cancel()
    }
  }

  private class Entry(val request: StyleImageRequest) {
    var content: StyleImageContent? = null
    var job: Job? = null
  }

  private data class Completion(val entry: Entry, val result: Result<StyleImageContent>)

  private data class ResolvedProperty(
    val value: JsonElement,
    val images: List<StyleImageDefinition>,
  )
}
