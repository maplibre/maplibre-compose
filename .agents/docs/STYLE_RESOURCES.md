# Loaded style resources

`MapStyleState` groups loaded sources, layers, and style images by resource
kind. Each resource kind exposes only the operations that its MapLibre object
supports.

This API replaces `MapStyleState.sources`, `MapStyleState.source(id)`, and
`MapStyleState.layer(id)`. The migration adds no compatibility aliases.

## Public API

`MapStyleState` exposes three stable objects:

```kotlin
public class MapStyleState internal constructor(...) {
  public val sources: StyleSources
  public val layers: StyleLayers
  public val images: StyleImages
}
```

The objects belong to `org.maplibre.compose.map`. Their constructors are
internal.

### Sources

`StyleSources` provides lookup, ordered iteration, and structural commands:

```kotlin
public class StyleSources internal constructor(...) : Iterable<SourceHandle> {
  public operator fun get(id: String): SourceHandle?

  public fun add(source: Source): SourceHandle

  public fun remove(id: String): Boolean

  override fun iterator(): Iterator<SourceHandle>
}
```

`get` returns a generation-bound handle for the loaded source. It returns null
while the style is not ready or when the ID is absent.

Iteration contains every source in the current loaded style. The order matches
the engine style order. Iteration is empty while the style is not ready.

`add` adds the source to the current loaded style and returns its handle. The
command fails when the style already contains the source ID.

`remove` removes a source from the current loaded style. It returns false when
the ID is absent. MapLibre can refuse removal when a layer still references the
source.

Source handles retain their current commands. These commands include GeoJSON
data updates, feature state, source queries, cluster queries, and custom-source
invalidation.

### Layers

`StyleLayers` provides lookup and ordered iteration:

```kotlin
public class StyleLayers internal constructor(...) : Iterable<LayerHandle> {
  public operator fun get(id: String): LayerHandle?

  override fun iterator(): Iterator<LayerHandle>
}
```

`get` returns a generation-bound handle for the loaded layer. It returns null
while the style is not ready or when the ID is absent.

Iteration contains every layer in the current loaded style, from bottom to top.
Iteration is empty while the style is not ready.

`LayerHandle` retains imperative property reads and writes. This API does not
add, remove, or move layers imperatively. Declarative layer operations require
the ordering and replacement rules in `StyleComposition`.

### Images

`StyleImages` provides structural commands only:

```kotlin
public class StyleImages internal constructor(...) {
  public fun add(
    id: String,
    image: ImageBitmap,
    sdf: Boolean = false,
    stretch: ImageStretch? = null,
  )

  public fun remove(id: String): Boolean
}
```

`add` installs a style image in the current loaded style. The command fails when
the style already contains the image ID.

`remove` removes a style image from the current loaded style. It returns false
when the ID is absent.

A style image has no handle or public inspection API. Style images support icon
and pattern lookup by the renderer, but they expose no commands comparable to
source data updates or layer property writes. An `ImageSourceHandle` remains a
source handle. It controls the bitmap, URI, and geographic bounds of an image
source.

## Loaded-style lifetime

The three resource objects remain attached to one `MapStyleState`. Their
contents and commands target the current ready loaded-style generation.

A source or layer handle belongs to the generation that created it. A base style
reload or an engine replacement invalidates the handle. A later resource with
the same ID does not make the old handle valid again.

Imperative source and image additions belong to the current generation. The
library does not replay them into a replacement generation. A retained engine
keeps the additions while the presentation is detached because the loaded-style
generation remains the same.

`StyleComposition` remains the only API for sources, layers, and images that
must survive a generation replacement.

## Ownership and reconciliation

Each loaded resource ID has one structural owner. The base style,
`StyleComposition`, or an imperative command can own the ID.

An imperative command cannot remove a source or image that the current
`StyleComposition` revision owns. The command fails before it changes the
engine. The reconciler tracks installed declarative resources and would
otherwise retain stale installation state.

An imperative addition cannot use an ID that the base style or the current
`StyleComposition` revision already uses. A later declarative revision cannot
claim an ID that an imperative addition uses in the current generation. The
revision fails with a duplicate-ID error instead of replacing either resource.

`MapState` and `MapSnapshotter` own the imperative resource records for their
current loaded-style generation. `StyleReconciler` remains the owner of
declarative installation records. Both paths check resource ownership before a
structural mutation reaches `StyleBinding`.

## Source metadata and attribution

`StyleSources` is the public registry for loaded source handles. It includes
base-style sources, declarative sources, and imperative sources.

The registry refreshes when a style becomes ready, after a successful source
addition or removal, and when an engine source-metadata event arrives. A stale
metadata event cannot replace the registry for a newer generation.

Attribution remains a derived operation over the general source registry:

```kotlin
public fun MapStyleState.attributions(): List<String> =
  sources
    .map(SourceHandle::attributionHtml)
    .filter(String::isNotEmpty)
    .distinct()
```

The attribution extension keeps no separate source collection or cache.

## Command results and failures

Structural commands require `StyleLoadState.Ready`. A command fails with an
`IllegalStateException` when no ready loaded-style generation exists or when the
generation changes during the command.

A command fails with `StyleHandleException` when an ID conflicts with another
owner or when MapLibre refuses the mutation. A failed command leaves the public
resource registry unchanged.

A successful command updates the public registry before it returns and requests
a render. A later engine metadata event can replace source metadata without
changing source order.

## Implementation requirements

`MapStyleState` creates each resource object once. The objects read current
handles from state-backed snapshots and send structural commands through their
attached owner.

The owner performs each command in this order:

1. Require a ready current `StyleBinding`.
2. Check the resource ID and its structural owner.
3. Apply the mutation through `StyleBinding`.
4. Confirm that the loaded-style generation is unchanged.
5. Update the public resource snapshot.
6. Request a render.

The common `StyleBinding` boundary gains point lookup for style-image IDs so
that `add` can reject duplicates and `remove` can report absence. The public API
does not expose that lookup.

Source and layer snapshots use the complete engine reads that already produce
`SourceHandle` and `LayerHandle` values. Snapshot publication preserves engine
order and uses the existing generation checks around source refreshes.

## Validation

Common tests cover these contracts:

- lookup and iteration before readiness, after readiness, and after invalidation
- source and layer iteration order
- source addition, removal, duplicate IDs, and engine refusal
- image addition, removal, and duplicate IDs
- rejection of mutations against declaratively owned resources
- duplicate ownership introduced by a later declarative revision
- stale handles after a base-style reload and an engine replacement
- retained-generation behavior while a presentation is detached
- attribution from base-style, declarative, and imperative sources
- stale source-metadata events after a generation replacement
- registry publication and render requests after successful commands

Platform tests cover one successful source mutation and one successful image
mutation on MapLibre Native FFI and MapLibre GL JS. Existing live-map tests
continue to cover the commands on each source and layer handle type.

Run the normal focused validation through mise tasks. The implementation must
pass `mise run check`, `mise run test:android`, `mise run test:desktop`,
`mise run test:ios`, and `caffeinate -dimsu mise run test:js`.

## Excluded API

This design adds no mutable `Map` implementation. A `Map` would expose generic
mutation methods that cannot enforce resource ownership or generation checks.

This design adds no image enumeration or `StyleImageHandle`. Image commands do
not need either API.

This design adds no imperative layer insertion, removal, or movement. Those
operations require declarative anchor, replacement, click-handler, and source
dependency state.
