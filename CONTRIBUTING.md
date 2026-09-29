# Contributing

For a feature or a bug fix without an issue,
[file one](https://github.com/maplibre/maplibre-compose/issues/new/choose) first
to discuss the change. New contributors can start with
[good first issues](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22);
[help wanted](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22)
issues need MapLibre, Android, iOS, or other expertise.

If you use AI assistance, follow the [AI policy](./AI_POLICY.md).

## Set up

Install [mise](https://mise.jdx.dev/getting-started.html) and run `mise install`
for the tool versions CI uses. `mise tasks` lists every task, and CI runs the
same tasks, so a local failure reproduces with the command the job ran.

Use IntelliJ IDEA or Android Studio with the
[Kotlin Multiplatform](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform),
[Android](https://plugins.jetbrains.com/plugin/22989-android), and
[Jetpack Compose](https://plugins.jetbrains.com/plugin/18409-jetpack-compose)
plugins; Kotlin Multiplatform has no stable LSP.

Some tools stay outside `mise install`:

- **Android SDK:** point `sdk.dir` in `local.properties` at an existing SDK and
  run `mise run android-sdk-packages`. Without one, `mise -E android install`
  installs the pinned SDK in a separate environment, because installing SDK
  packages accepts their licenses; run Android tasks with `MISE_ENV=android`.
- **Xcode:** `mise run install-xcode`. It is several gigabytes, so
  `mise install` only fetches the `xcodes` CLI that manages it.
- **Vulkan:** the desktop tests drive a real GPU through a headless Vulkan
  device and fail on a host without one. macOS gets MoltenVK through LWJGL.

Desktop uses the published
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi)
bindings, so it needs no C++ toolchain. The browser embeds a patched MapLibre GL
JS that Gradle builds automatically; see the
[patch workflow](patches/maplibre-gl-js/README.md) to change it. The
[benchmark guide](benchmarks/README.md) covers performance measurements.

## Check changes by hand

CI tests the AWT desktop host. For desktop bridge changes, also run
`demo:desktop` and `demo:desktop-nucleus` with each backend and check resize,
zoom, pan, and window closure.

The browser demo serves a Compose HTML ferry departure board at `/?ferries`.

### Android Auto

`mise run demo:android-auto` needs the
[Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu):

1. On a connected phone, install and update Android Auto, enable its developer
   mode, allow unknown sources, and start the head unit server from its
   developer menu.
2. Install **Android Auto Desktop Head Unit Emulator** from Android Studio's SDK
   Tools, or run `sdkmanager 'extras;google;auto'`.
3. Run the task and select **MapLibre** in the car launcher.

For Surface integration changes, check map rendering, pan/zoom, recentering,
day/night mode, window resizing, and reconnecting the host.

### CarPlay

Run `mise run demo:ios`, choose **I/O > External Displays > CarPlay** in
Simulator, and open **maplibre-compose-demo** in the car launcher. Check map
rendering, pan/zoom, recentering, day/night mode, and reconnecting the CarPlay
display while the phone app stays open.

Only simulator builds carry the CarPlay maps entitlement. A physical CarPlay
connection needs Apple's
[CarPlay entitlement approval](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements),
a matching provisioning profile, and `CODE_SIGN_ENTITLEMENTS` set to
`iosApp/CarPlay.entitlements` for the device SDK.

## Documentation and versions

Build the site with `mise run build:docs` or serve it with `mise run //docs:dev`
rather than calling Astro or Gradle directly. The tasks pass versions derived
from Git tags, which the site quotes as dependency coordinates; Gradle alone
uses the `0.0.0` placeholders from `gradle.properties`.

Releases are tagged `vMAJOR.MINOR.PATCH`. Any other commit builds as a snapshot
of the next patch; `mise run version` prints what this checkout builds as.

## Native owner threads

MapLibre Native binds a map to the thread that created it. Each native map
session and snapshotter runs one `MlnFfiMapRuntimeLoop`, and only that loop's
owner thread calls a `MapHandle`, apart from the renderer thread attaching its
render session. Code already on the owner thread calls the engine directly, and
so do the non-suspending `MlnFfiStyleBinding` methods that return a value or
throw on refusal; those methods throw on any other thread. `MlnFfiStyleBinding`
lists the writes that queue themselves and work from any thread. Code on another
thread uses one of three loop operations, whose exact behavior the loop's KDoc
lists:

- `await` when the caller needs a result or needs to know that the work ran. It
  suspends instead of blocking, so no caller thread waits on the owner (#1529).
  It ends its batch, so native can render between reads (#1475).
  `cancellable = false` is for work that must finish even when the caller is
  cancelled: work that uses native memory the caller frees when the call
  returns, or a detach that must stop the departed lease from receiving events.
- `submit` when the caller needs nothing back. It runs inline on the owner, so
  the writes of one style commit stay in one owner task (#1511). With
  `ordered = true`, native delivers the action's events before later work runs:
  a transition start retires superseded anchor IDs (#1430), and a gesture end
  completes its fence after the gesture's events (#1290). `onDropped` runs
  whenever the action does not finish, so a waiting caller is always released.
- `awaitEventsDrained` when work must wait until the events raised so far have
  been handled, such as installing a presentation's event producer.

Only `await` and ordered `submit` end their batch. Each native pump can take up
to 4 ms, which gestures and tile answers would otherwise pay on every call.

## Make CI happy

`mise run check` reports problems and `mise run fix` rewrites what it can; the
pre-commit hook that mise installs runs them on staged files.
`mise run lint:android` runs Android Lint, which CI runs in the same job.

## Pull requests

Prefer Conventional Commits for PR titles, which become the squash-merge commit
message.
