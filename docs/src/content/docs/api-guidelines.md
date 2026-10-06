---
title: API guidelines
description: Rules for the public API of the published libraries.
---

Rules for the public API of the published libraries. This guide is intended for
v1.0.0 and later; the current API doesn't fully conform to it yet.

## 1. About this guide

This guide covers every `public` or `@PublishedApi` declaration in the published
libraries. It doesn't cover internal code, the demo app, or the benchmarks.

The API is designed for Kotlin callers using Compose. Calling it from Java or
any other JVM language except Kotlin isn't supported, so a declaration that is
awkward or impossible to call from Java is fine. Examples: value classes in
signatures, which mangle JVM method names, and missing `@JvmOverloads`,
`@JvmStatic`, or `@JvmName`.

Each section lists rules, then examples. A rule taken from an upstream guide
ends with a bracketed number, such as [1], that links to the relevant section;
the guides are also listed under Sources at the end. If a rule doesn't fit a
case, change the rule in the same PR instead of making an exception in code.

Rules enforced deterministically, such as by a linter, should be removed from
this guide.

### Departures from upstream guides

- Two-letter acronyms are treated as words, like longer ones: `UiKitMapHost`. In
  Kotlin conventions, two-letter acronyms are all capitals, as in `IOStream`
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#naming-rules), but
  one rule for all lengths is simpler, and Compose uses the same rule in
  `UiComposable`.
- Constants and enum entries use PascalCase, as in Compose
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#singletons-constants-sealed-class-and-enum-class-values).
  Kotlin conventions specify screaming snake case for constants
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#property-names).
- State holders are final classes, not the interfaces Compose recommends
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#extensibility-of-hoisted-state-types).
  Map state is backed by the engine, so a caller's implementations couldn't
  work, and each new member would break every implementation.
- Data classes are allowed, contrary to the Kotlin library guidelines
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#avoid-using-data-classes-in-your-api),
  when their fields are fixed by definition, when their constructor is internal,
  or when new fields are added last with `@IntroducedAt` (section 12). Their
  generated `componentN` functions still make field order part of the API.

## 2. Naming

- Name classes with nouns and functions with verbs. Avoid filler words such as
  `Manager` and `Wrapper`.
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#choose-good-names)
- Name composables that return `Unit` with PascalCase nouns. Name composables
  that return a value like other functions.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#naming-unit-composable-functions-as-entities)
- Start the name of a composable that remembers and returns a mutable object
  with `remember`.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#naming-composable-functions-that-remember-the-objects-they-return)
- Treat acronyms and initialisms as words in code, including two-letter ones and
  ones with digits: `Maplibre`, `GeoJson`, `D3d11`. In prose and KDoc, use the
  usual spelling: MapLibre, GeoJSON, UI. Departs from
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#naming-rules).
- Use PascalCase for constants, singleton objects, and enum entries.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#singletons-constants-sealed-class-and-enum-class-values)
  Departs from
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#property-names).
- Start `CompositionLocal` names with `Local`.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#naming-compositionlocals)
- When the MapLibre style spec has a term for something, use it, in camel case.
- Name expression DSL functions after the style-spec operator they build, even
  when the name isn't a verb (`const`, `coalesce`). Write operators that take no
  arguments as functions, not constants: `pi()`, not `PI`.
- Name a component's defaults object after the component, with a `Defaults`
  suffix.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#ComponentDefault-object)
- Prefix a foundation component with `Basic` when the Material 3 module has a
  component with the same name.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#BasicComponent-vs-Component)
- End receiver scope interface names with `Scope`.
- Name opt-in annotations `<Adjective>MaplibreComposeApi`.
- Use lowercase, singular package names.
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#naming-rules)
- Put internal code in an `.internal` subpackage of the package it supports.
- Name packages after features. Don't add catch-all packages such as `util`.

```kotlin
// Do
class GeoJsonOptions
class UiKitMapHost
fun installMaplibreCompose()
fun pi(): Expression<FloatValue>
fun rememberMapState(): MapState
val LocalViewport = staticCompositionLocalOf<Viewport> { error("No viewport") }
enum class QuickZoomDirection { UpZoomsIn, DownZoomsIn }
val pitch: Double                      // style spec term
object LocationIndicatorDefaults
@Composable fun BasicCompassButton()  // overlay
@Composable fun CompassButton()       // material3
// package org.maplibre.compose.layer
// package org.maplibre.compose.layer.internal

// Don't
class GeoJSONOptions
class UIKitMapHost
fun installMapLibreCompose()
val PI: Expression<FloatValue>
fun mapState(): MapState
val ViewportLocal = staticCompositionLocalOf<Viewport> { error("No viewport") }
enum class QuickZoomDirection { UP_ZOOMS_IN, DOWN_ZOOMS_IN }
val tilt: Double
class OfflineManager
// package org.maplibre.compose.layers
// package org.maplibre.compose.util
```

## 3. General API design

- Build on a small set of core operations, and add conveniences that combine
  them.
  [[4]](https://kotlinlang.org/docs/api-guidelines-simplicity.html#define-and-build-on-top-of-core-api)
- Keep a type's members to its core concept. Put extras in extension functions
  and properties.
  [[4]](https://kotlinlang.org/docs/api-guidelines-readability.html#use-extension-functions-and-properties)
- Prefer small pieces that callers combine over more parameters.
  [[4]](https://kotlinlang.org/docs/api-guidelines-readability.html#prefer-explicit-composability)
- Use the same parameter names and order across related functions, from general
  to specific.
  [[4]](https://kotlinlang.org/docs/api-guidelines-consistency.html#preserve-parameter-order-naming-and-usage)
- Make overloads of a function behave the same way.
  [[4]](https://kotlinlang.org/docs/api-guidelines-consistency.html#preserve-parameter-order-naming-and-usage)
- Avoid `Boolean` arguments whose meaning isn't clear at the call site. Named
  `Boolean` builder properties are fine.
  [[4]](https://kotlinlang.org/docs/api-guidelines-readability.html#avoid-using-the-boolean-type-as-an-argument)
- Reject invalid arguments with `require` and invalid state with `check`, with a
  message that describes the problem and includes the rejected value.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#validate-inputs-and-state)
- Return `null` when data can't be found or computed. Don't use exceptions for
  control flow.
  [[4]](https://kotlinlang.org/docs/api-guidelines-consistency.html#choose-the-appropriate-error-handling-mechanism)
- When state can change outside the caller's control, and the caller can't check
  it first without a race, return `null` or an empty result instead of throwing.
  For example, a base-style layer handle expires when the engine reloads the
  style: its reads return `null`, and its writes do nothing and log a warning.
  Using an object after the caller closed it is a programming error, and throws.
- Throw a library exception type only for failures that callers handle
  specifically, such as `MapSnapshotException`. Library exception types extend
  `RuntimeException` and have internal constructors.
- Make behavior unsurprising instead of documenting a surprise. When behavior is
  wrong, fix it instead of documenting it.
- Give stateful types a `toString` that shows their contents in a consistent
  format, without secrets such as access tokens.
  [[4]](https://kotlinlang.org/docs/api-guidelines-debuggability.html#provide-a-tostring-method-for-stateful-types)
- Don't keep state in global variables or stateful top-level functions. Let
  callers pass the object in.
  [[4]](https://kotlinlang.org/docs/api-guidelines-testability.html#avoid-global-state-and-stateful-top-level-functions)

```kotlin
// Do
camera.animateTo(position)
camera.snapTo(position)
require(maximumFps == null || maximumFps > 0) { "maximumFps must be positive, was $maximumFps" }

// Don't
camera.moveTo(position, true)
require(maximumFps == null || maximumFps > 0)
```

## 4. Types that may grow

Every enum, sealed type, and set of named values is one of four kinds. Choose
the kind by what any later minor release might expose, because changing the
shape later is a breaking change.

| Kind           | Use when                                                                                                                       | Declare as                                                                                      | Example              |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- | -------------------- |
| Closed         | The values can never change, because they describe math, a fixed protocol, or a complete split                                 | `enum class`                                                                                    | `QuickZoomDirection` |
| Open input     | Callers only pass values in, and the library won't return them in any later minor release                                      | `enum class`, or a `sealed` type when cases carry data                                          | `TileLodAlgorithm`   |
| Open identity  | A raw value from outside the library, such as a style-spec or engine name, possibly one with no named constant in this version | `value class` with an internal constructor, a public `value`, and named values on the companion | `LineCap`            |
| Open structure | The library returns it, including sets of names the library defines                                                            | `sealed interface` with an internal subtype, and a `data object` for each case without data     | `MapEvent`           |

- State the kind in KDoc: "Closed." or "Values may be added in minor releases;
  use an `else` branch when matching."
- Closed promises that no value is added before the next major release. Choose
  it only when you can't name a value a later release might add; when in doubt,
  choose an open kind.
- Use sealed types, internal subtypes, and internal constructors so that callers
  can't create unexpected values.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#prevent-unwanted-and-invalid-extensions)
- Keep unrecognized values in an open identity. Don't map them to a named value
  such as `Unknown`.
- When a value means "no answer", such as an unclassified failure or a setting
  the platform doesn't report, use `null` instead of a named catch-all such as
  `Unknown` or `Other`. Name a case `Unknown` only when the engine or platform
  reports a real value with that meaning.
- Use a value class only to wrap a raw value from outside the library. When the
  library defines the names itself, use an open structure instead of inventing a
  raw value.
- Give an open structure an internal subtype, so that callers' `when` needs an
  `else` branch.
- Group named values on a companion object or a sealed interface, not a plain
  `object`. When a value belongs to several sealed types, nest it in one and
  implement the others:
  `sealed interface CameraAction { data object Pan : CameraAction, DragAction }`.

```kotlin
// Open identity: can hold values with no named constant in this version
@JvmInline
public value class LineCap internal constructor(override val value: String) : EnumValue {
  public companion object : EnumType<LineCap> {
    public val Butt: LineCap = LineCap("butt")
    public val Round: LineCap = LineCap("round")
    public val Square: LineCap = LineCap("square")
    override val entries: List<LineCap> = listOf(Butt, Round, Square)
  }
}

// Open structure: callers can't reference the internal subtype, so their `when` needs `else`
public sealed interface MapEvent {
  public class CameraMoved internal constructor(public val reason: CameraMoveReason) : MapEvent
}
internal object UnspecifiedMapEvent : MapEvent
```

## 5. Options and configuration

Settings objects that might gain fields use the shape shown below.

- Use a class with a private or internal constructor and a builder, so that new
  options don't change any constructor. It may be a data class (section 1);
  otherwise write `equals`, `hashCode`, and `toString`.
- Take a `from` parameter in the builder instead of providing `copy`, and
  default it to a standard preset on the companion object.
- Take required values with no sensible default, such as an ID, as constructor
  parameters before `block`.
- Within a major version, don't start rejecting a value that an earlier release
  accepted.
- Make every option that all platforms support settable from common code, with
  defaults that need no platform setup.
  [[4]](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html#design-apis-for-use-from-common-code)
- When settings differ by source set, such as JS and native, declare the options
  class and its builder as `expect` classes. The common declarations hold only
  what every platform shares, and each `actual` adds its platform's settings.
  Provide presets as companion `val`s named for their purpose, so that callers
  in common code can still select one.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#do-the-right-thing-by-default)
- A small value that callers construct directly, such as the four edges of
  `DpPadding`, can be a data class with a public constructor. If it might gain
  fields, add them last with `@IntroducedAt` (section 12).

```kotlin
@Immutable
public class RenderOptions private constructor(builder: Builder) {
  public val maximumFps: Int? = builder.maximumFps
  public val tileLod: TileLodOptions = builder.tileLod

  public constructor(from: RenderOptions = Standard, block: Builder.() -> Unit) :
    this(Builder(from).apply(block))

  // equals, hashCode, and toString

  @MapOptionsDsl
  public class Builder internal constructor(from: RenderOptions?) {
    public var maximumFps: Int? = from?.maximumFps
    public var tileLod: TileLodOptions = from?.tileLod ?: TileLodOptions.Standard
  }

  public companion object {
    public val Standard: RenderOptions = RenderOptions(Builder(from = null))
  }
}

val options = RenderOptions(from = RenderOptions.Standard) {
  maximumFps = 30
  tileLod = TileLodOptions.Performance
}
```

## 6. Composables

### Parameters

- Order parameters: required, then `modifier: Modifier = Modifier`, then
  optional, then an optional trailing lambda.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#Parameters-order)
- Apply `modifier` once, as the first modifier on the root layout. Add your own
  modifiers after it, never before.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#elements-accept-and-respect-a-modifier-parameter)
- Don't add a parameter for something a `Modifier` can do, unless it's core to
  the component.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#Parameters-vs_Modifier-on-the-component)
- Emit content or return a value, never both.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#emit-xor-return-a-value)
- Write each default value inline or on the `XDefaults` object, and never call
  internal code from it.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#Default-expressions)
- Make a parameter nullable only when absence means something, never to mean
  "use the default".
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#Nullable-parameter)
- Don't take `State<T>` or `MutableState<T>`. Take a value, or a `() -> T`
  lambda for values that change often.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#State_T_as-a-parameter)
- For a value callers might want to set, such as a theme color, read the
  `CompositionLocal` in a parameter's default value, not in the implementation.
  [[2]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md#Explicit-vs-implicit-dependencies)
- Group related settings into one options parameter (section 5) that defaults to
  a named preset.

### State

- Hoist caller-controlled state into a `@Stable` class named `XState`, created
  by `rememberXState()`. Departs from
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#extensibility-of-hoisted-state-types)
  by using a class instead of an interface.
- Default the `state` parameter to `rememberXState()`, never to `null`.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#default-policies-through-hoisted-state-objects)
- Mark public types `@Stable` or `@Immutable` when they qualify, and never
  remove the annotation.
  [[1]](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md#stable-types)

### Changing a signature

- To add a parameter, put it at the end (before any trailing lambda) with a
  default value, and annotate it with `@IntroducedAt` (section 12).

```kotlin
@Composable
public fun MapButton(
  onClick: () -> Unit,                                  // required
  modifier: Modifier = Modifier,                        // first optional
  colors: MapButtonColors = MapButtonDefaults.colors(), // public default
  content: @Composable () -> Unit,                      // trailing lambda
)

LineLayer(id = "routes", source = routes) {
  color = const(Color.Blue)
  interactions {
    click { event -> ClickResult.Consume }
  }
}

// Adding `enabled` in 1.2
@OptIn(ExperimentalVersionOverloading::class)
@Composable
public fun MapButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  colors: MapButtonColors = MapButtonDefaults.colors(),
  @IntroducedAt("1.2") enabled: Boolean = true,
  content: @Composable () -> Unit,
)
```

## 7. Callbacks

- Give a callback no parameters, or one parameter of a library type with an
  internal constructor, so that the type can gain fields.
- Don't give callbacks a receiver. Inside a callback with a receiver, `this`
  refers to the receiver instead of the enclosing class. This covers callbacks
  that the library calls later. DSL builder blocks and blocks that run before
  the function returns, such as `withPlatformMap { }`, may use a receiver.
- Lambdas that run work for the library or supply a value, such as
  `(Runnable) -> Unit` or `() -> T?`, can be plain function types.
- Name the callback parameters of composables `onX`.
- Declare every named callback type as a `fun interface`, not a typealias, so
  that they're consistent and each has its own KDoc.
- Name a builder function that sets a handler after the event, without `on`.
  Calling it again replaces the handler.
- Make a callback that may wait on I/O a `suspend` function.
- Document which thread each callback runs on, whether it can run in parallel or
  reentrantly, and what happens if it throws.
  [[4]](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html#document-lambda-parameters)
- Handle an exception from a callback by what the callback does:
  - A callback that does I/O, such as a resource or tile provider, can fail. Its
    exception means that one request, tile, or image failed, and the library
    reports it the way it reports that kind of failure.
  - In any other callback, such as a predicate or a builder block, an exception
    is a bug. Let it fail the operation that ran the callback.
  - A logger's exceptions are dropped, because logging must never break the app.
- Make an operation that produces a final result, such as a snapshot, fail as a
  whole when part of it fails, instead of returning an incomplete result.
- Keep interfaces that callers implement small, and give any new member a
  default implementation.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#allow-opportunities-for-extension)

```kotlin
// Do
public fun interface MissingImageResolver {
  public suspend fun resolve(request: MissingImageRequest): ResolvedStyleImage?
}

interactions {
  click { event ->
    select(event.hits.first().feature)
    ClickResult.Consume
  }
}

// Don't
public typealias MissingImageResolver = suspend (id: String) -> ResolvedStyleImage?

interactions {
  click { // `this` is the event
    select(hits.first().feature)
    true
  }
}
```

## 8. Types the library creates

Events, requests, hits, stats, and summaries are created by the library and only
read by callers.

- Make constructors internal.
- Data classes are fine. Add new fields only after existing ones, because
  `componentN` follows field order. Departs from
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#avoid-using-data-classes-in-your-api).
- Make engine-backed types, such as `MapRuntime` and `MapSnapshotter`, final
  classes or sealed interfaces, like state holders.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#prevent-unwanted-and-invalid-extensions)
- Make scopes, such as `MapOverlayScope`, `sealed`.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#prevent-unwanted-and-invalid-extensions)
- Accept and return read-only collections, and copy collections that are mutable
  underneath.
  [[4]](https://kotlinlang.org/docs/api-guidelines-predictability.html#avoid-exposing-mutable-state)

```kotlin
public data class RenderStats internal constructor(
  public val frameCount: Long,
  public val drawCallCount: Long,
  public val droppedFrameCount: Long, // added later, so it goes last
)

public sealed interface MapSnapshotter
```

## 9. Platform-specific APIs

- Put as much of the API as possible in common code.
  [[4]](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html#design-apis-for-use-from-common-code)
- Make common APIs behave the same on every platform where practical, and
  document any difference, such as in how invalid input is handled.
  [[4]](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html#ensure-consistent-behavior-across-platforms)
- Put a platform-specific declaration in its feature's package, with a platform
  prefix.
- Use one prefix per platform: `Android`, `Apple` (iOS and macOS), `Ios`,
  `Macos`, `Desktop` (JVM), `Linux`, `Windows`, or `Web`. A backend or toolkit
  name follows the platform prefix: `DesktopMetalGpuContext`.
- Declarations in a source set shared by several platforms, such as the MapLibre
  Native one, take no prefix.
- Leave a feature out of the source set of a platform that doesn't support it,
  instead of adding a `canX` check. When it must stay in common code, reads
  return an empty result, and writes throw `UnsupportedOperationException` or do
  nothing, as documented.
- Don't name public packages after platforms or engine bindings.
- For extra platform arguments, add an overload of a common factory function.
- In artifacts that only provide a runtime, put public declarations in the
  package of the feature they implement.

```kotlin
// Do: package org.maplibre.compose.map
public class AndroidMapPresentation
public class WebMapPresentation

// Don't: package org.maplibre.compose.browser
public class BrowserMapPresentation
```

## 10. Units and value types

- Use a typed value when one exists for the quantity (see the table).
  [[4]](https://kotlinlang.org/docs/api-guidelines-simplicity.html#reuse-existing-concepts)
- For plain numbers, choose the type by how the value is used: an integer for
  discrete values, and a floating-point type for continuous ones. Use the type
  that the value is processed as, so values round-trip unchanged: `Float` where
  Compose or the engine uses `Float`, such as a pixel ratio, and `Double`
  otherwise.
  [[4]](https://kotlinlang.org/docs/api-guidelines-readability.html#use-numeric-types-appropriately)
- Don't use number types for identifiers.
  [[4]](https://kotlinlang.org/docs/api-guidelines-readability.html#use-numeric-types-appropriately)
- When a plain number's type doesn't show its unit, end its name with the unit.
- The table lists common quantities. For others, apply the rules above.

| Quantity                              | Type                                                          |
| ------------------------------------- | ------------------------------------------------------------- |
| Duration                              | `kotlin.time.Duration`                                        |
| Geographic position and bounds        | spatial-k `Position` and `BoundingBox`                        |
| Real-world distance                   | spatial-k `Length`                                            |
| Direction of travel or heading        | spatial-k `Bearing`                                           |
| Screen distance                       | Compose `Dp`                                                  |
| Insets passed in composition          | Compose `PaddingValues`, resolved with `LocalLayoutDirection` |
| Insets in stored data or image pixels | `DpPadding`                                                   |
| Screen density                        | Compose `Density`                                             |
| Color                                 | Compose `Color`                                               |

```kotlin
val animationDuration: Duration    // Do
val animationDurationMillis: Long  // Don't, when Duration fits
val tileZoom: Int                  // discrete
val zoom: Double                   // continuous
val uptimeMillis: Long             // plain number, unit in the name
```

## 11. Documentation

- Write KDoc for every public declaration, except overrides that add nothing.
  [[3]](https://kotlinlang.org/docs/coding-conventions.html#coding-conventions-for-libraries)
- Write KDoc as the contract: behavior, parameters, return values, lifecycle,
  threading, and platform limits. Don't describe how it's implemented.
- Start with one sentence that says what the declaration does, without restating
  the signature.
  [[4]](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html#thoroughly-document-your-api)
- State valid input ranges, what happens with invalid input, and every
  exception.
  [[4]](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html#thoroughly-document-your-api)
- Add a sample only when usage isn't clear from the signature and KDoc.
- Link related declarations with `@see` or KDoc links.
  [[4]](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html#use-explicit-links-in-documentation)
- Document behavior once, in common code. Document platform differences that
  can't be avoided.
  [[4]](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html#ensure-consistent-behavior-across-platforms)
- Write simple English, without idioms, Latin abbreviations, or jargon.
  [[4]](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html#use-simple-english)
- Keep contracts out of the docs site. Site pages introduce concepts and common
  tasks, and link to the API reference for details.

```kotlin
/**
 * Shows a button that turns the map back to north.
 *
 * Tapping it animates the camera bearing to 0.
 *
 * @param modifier The [Modifier] to apply to the button.
 * @param size The button's width and height. Must not be negative.
 * @throws IllegalArgumentException if [size] is negative.
 * @see ScaleBar
 */
```

## 12. Stability and compatibility

Within a major version, stable APIs (see Stability annotations) keep binary,
source, and behavior compatibility:
[[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#compatibility-types)

- Code compiled against an earlier release keeps linking and running.
- Source code keeps compiling, except for deprecated declarations.
- Documented behavior and serialized formats don't change.

These guarantees don't cover a `when` with no `else` branch over a type that may
grow (section 4), or callers in JVM languages other than Kotlin (section 1).

To keep these guarantees:

- Don't widen or narrow a return type.
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#avoid-widening-or-narrowing-return-types)
- Use caution when adding a public `const val` or `inline` function. Their
  values and bodies are copied into callers' compiled code.
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#considerations-for-using-the-publishedapi-annotation)
- Don't remove targets from an annotation's `@Target`. Don't add
  `VALUE_PARAMETER`, `PROPERTY`, or `FIELD` to an annotation that already allows
  one of the others, because existing uses on constructor properties could move
  to a different element.
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#avoid-changing-annotation-targets)
- To add a parameter to a public function or constructor, put it last with a
  default value and annotate it with `@IntroducedAt` and the release version.
  The compiler generates hidden overloads for callers compiled against earlier
  releases, including overloads of a data class's `copy`.
  [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#use-overloads-to-preserve-binary-compatibility)
- Keep the serialized names of fields. When renaming a property, keep reading
  its old name with `@JsonNames`.
- Prefer sealed types over interfaces that callers implement. When callers must
  implement an interface and implementing it is easy to get wrong, require
  opt-in for implementations with `@SubclassOptInRequired` instead of for every
  use.

### Stability annotations

| Annotation                       | Meaning                                                                                                           |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `ExperimentalMaplibreComposeApi` | May change in any minor release. For new APIs without much real use, and APIs that expose dependencies below 1.0. |
| `DelicateMaplibreComposeApi`     | Stable, but easy to misuse. KDoc explains how.                                                                    |
| None                             | Stable.                                                                                                           |

### Deprecation cycle

1. Required: in a minor release, mark it `@Deprecated` at `WARNING`, with
   `ReplaceWith` when there is a replacement.
   [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#evolve-apis-pragmatically)
2. Optional: in a later minor release, raise it to `ERROR`, then to `HIDDEN`,
   which keeps the declaration in the binary. Departs from
   [[4]](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#evolve-apis-pragmatically),
   which requires all three.
3. In the next major release, delete it.

## Sources

1. [API Guidelines for Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md):
   naming, state hoisting, and stability for composables.
2. [API Guidelines for @Composable components in Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md):
   parameters, defaults, layering, documentation, and evolution of components.
3. [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html):
   naming, and the extra conventions for libraries.
4. [Kotlin library authors' guidelines](https://kotlinlang.org/docs/api-guidelines-introduction.html):
   backward compatibility, predictability, documentation, and multiplatform
   design.

Each rule taken from one of these guides ends with its number, linked to the
relevant section. Rules without a citation are this project's own.
