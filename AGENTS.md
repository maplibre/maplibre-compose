# Repository guidance

MapLibre Compose wraps MapLibre for Kotlin Multiplatform. Native platforms use
`maplibre-native-ffi`; the browser uses hand-written MapLibre GL JS bindings.

## Priorities

The published libraries are the product. What matters most there is correct
behavior on every supported platform and a clear API. Until 1.0, a release may
break source and binary compatibility to reach a better API; users upgrade by
following the migration notes in PR descriptions.

Everything else serves maintainers: the demo app, benchmarks, code metrics,
docs-site tooling, CI scripts, and planning documents. Maintainers run these
themselves and see failures right away, so they need to work for the ways we use
them, without anticipating every misuse, environment, or concurrent run. The
demo also shows users how to call the library, so its library usage should be
idiomatic.

MapLibre behavior is whatever the pinned MapLibre GL JS submodule and
`maplibre-native-ffi` release do. Answer questions about engine behavior from
those sources.

## Development

Use mise tasks: `mise tasks --all` lists them, and `CONTRIBUTING.md` covers
setup outside mise and the demo checks CI cannot run. For work that mise does
not cover, run one named Gradle task per invocation. `./gradlew build` builds
every target, including iOS release frameworks, and can exhaust memory.

- Static checks: `mise run check`; automatic fixes: `mise run fix`.
- Android Lint: `mise run lint:android`.
- Tests: `mise run test:android`, `test:android:device`, `test:ios`, `test:js`,
  `test:macos`, or `test:desktop`.
- Documentation: `mise run build:docs` or `mise run //docs:dev`. These tasks
  supply versions derived from Git tags; direct Gradle builds use placeholders.

Checks, tests, and demo launches use local, disposable state. Run the ones a
change needs without asking first.

Keep tests that would catch a regression in the changed behavior, sized like the
neighboring tests; scratch checks used while working need not be committed.

Choose platforms by what the change can break. Shared Kotlin and calls to
existing FFI APIs can be validated on one platform, usually desktop; OS APIs,
GPU backends, loading, and packaging need their own platforms. "Native" in this
repository means the MapLibre Native backend used by desktop, Android, and iOS.

### Test environment

- Tests that need a MapLibre runtime and a Compose UI test host go in
  `liveMapTest`. `commonTest` has neither, because Android host tests inherit
  it. Android host and device tests live in `androidHostTest` and
  `androidDeviceTest`.
- Browser tests run real maps in Chromium, Firefox, and WebKit through Kotlin's
  Playwright runner. `mise run test:js` installs the browsers and their Linux
  system dependencies.
- Android SDK lookup is `local.properties`, then `ANDROID_HOME`, then
  `ANDROID_SDK_ROOT`. `mise run android-sdk-packages` installs required
  packages. Without an SDK, run `mise -E android install` and then run Android
  tasks in that environment, for example `mise -E android run test:android`.

### Build conventions

- Dependency, plugin, Android SDK, and JVM versions belong in
  `gradle/libs.versions.toml`. `gradle.properties` holds build switches and
  placeholder release versions.
- `.mise/bin/version-args` derives published versions from `vMAJOR.MINOR.PATCH`
  tags and passes them to Gradle, so Gradle configuration does not depend on the
  checkout's Git state. Keep Git access in mise tasks and scripts.
- CI jobs call mise tasks, so a job's command lives in its task. Workflows and
  composite actions pin each third-party action to a commit SHA with a
  `# version` comment, the same pin everywhere it is used;
  `mise run ci:check-action-pins` checks this. Dependabot updates both
  `.github/workflows` and `.github/actions/*`.

## Demo app

`demo-app/common` is the demo's only Kotlin Multiplatform module and contains
the shared app. Android (phone and TV), AWT desktop, Nucleus desktop, native
macOS ARM64, and iOS modules launch it; the browser entry point is in
`common/src/jsMain`. Android Auto and CarPlay share a small map demo with native
controls, and `demo-app/wearos` presents a map with Wear Compose controls.

Launch the demo directly in a screen instead of tapping through the menu. Every
launcher reads `route`, `camera`, and `extent`:

- `route`: `demo/<id>`, `benchmarks`, `benchmark/<id>`, `settings`, or
  `settings/<location|input|camera|rendering>`. A demo id is its name in
  lowercase with hyphens (`demo/live-tracking`); a benchmark id is the
  scenario's id (`benchmark/animation`).
- `camera`: `zoom/latitude/longitude[/bearing[/pitch]]`, the MapLibre GL JS hash
  format. A demo route with a camera keeps it instead of flying.
- `extent`: `WIDTHxHEIGHT` in dp, desktop and native macOS only. `400x800` is
  the compact bottom-sheet layout; `900x700` and `1200x800` are the medium and
  expanded sidebars.

`mise run demo:desktop`, `demo:desktop-nucleus`, and `demo:android` take them as
`--route`, `--camera`, and `--extent` flags. Without the tasks: desktop and
macOS binaries take `--route=<route>` and friends on the command line, Android
reads string extras
(`adb shell am start -n
org.maplibre.compose.demoapp/.MainActivity --es route demo/castello-plan`),
iOS takes the same command-line form through `xcrun simctl launch`, and the
browser reads the query string (`/?route=settings/input`).

On Android, record animations with `adb shell screenrecord` and split the frames
with ffmpeg; single screencaps miss a 300ms transition.

For Material Symbols, use the Android vector XML in Google's
[symbols/android](https://github.com/google/material-design-icons/tree/master/symbols/android).

## Documentation

Documentation describes the library as it is, for a reader who does not know its
history. Change it when a code change makes existing text wrong or changes what
a reader would do, and fix that text in place. Notes about what changed ("now",
"no longer", migration steps) belong in the PR description, where the reviewer
who knows the old behavior reads them.

Each layer carries a different level of detail:

- Site pages in `docs/src/content/docs/` help a user integrate the library,
  complete a task, or understand a concept. They cover the common path and the
  decisions most users make. Edge cases, platform differences, and exact
  contracts belong in the API reference.
- KDoc is the API contract: behavior, parameter meaning, lifecycle, threading,
  and platform limits that callers rely on.
- `CONTRIBUTING.md` explains project-specific decisions to contributors.

Library users know Compose but often not MapLibre, and many read English as a
second language, so say what the API does in literal terms.

Site pages import Kotlin examples from `// #region` blocks in
`demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets/`, which compile
with the demo app, so examples stay correct as the API changes. Add or update a
region for each Kotlin example.

## Pull requests

Follow [PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md). Use draft
status for unfinished work, unresolved decisions, or generated code pending
human review. Prefer Conventional Commits for PR titles, which become the
squash-merge commit message.

Write the description for a reviewer who has not seen your working session, and
for someone reading the history later. Aim for the shortest description that
lets them understand and trust the change; the diff carries the details.

- Start from the problem as a user or maintainer experiences it.
- Explain what behaves differently, in the project's and MapLibre's vocabulary.
- If library users must change their code, or the PR knowingly leaves part of
  the problem unsolved, say so.

Validation covers what CI does not show. CI runs the checks and test suites on
every PR, so passing them needs no mention. Describe how the tests changed and
what they now catch, and anything checked outside CI: benchmark comparisons for
performance work, code metrics for refactors, and manual checks in the demo for
UI changes. Name any affected behavior that went unverified.

### CI tiers

`ci/plan.py` selects jobs from `ci/jobs.json` for each event. Each tier has a
caller workflow with its own required check and a tier workflow containing its
jobs. Single-run jobs live in the tier workflow; jobs with multiple
configurations use a reusable workflow. Draft PRs run Android API 36, JS, Linux
x64 desktop, docs, hygiene, ABI validation, and iOS device compilation. Ready
PRs add Android API 26, the iOS simulator on the runner's newest runtime, macOS
desktop, macOS Native ARM64, and Windows x64. Dependabot PRs, main, and manual
runs include every variant.

The `ci:full` label adds the Linux and Windows ARM64 variants and the iOS 15.5
simulator. The ARM64 jobs catch architecture-specific failures, so request the
label for a change that could behave differently there: ABI or pointer layout,
architecture-specific artifact selection, loading or linking, or toolchain and
runner configuration. Shared Kotlin, existing FFI calls, and unrelated CI tasks
are covered by the default tiers. When requesting it
(`gh pr edit <number> --add-label 'ci:full'`), say in the PR which platform
could fail and why. The label works on drafts, persists across pushes, and runs
only the tiers the PR has not yet run.

## Code review rules

Weigh each finding against the priorities above: how likely the problem is in
real use, and what it costs the people it affects.
