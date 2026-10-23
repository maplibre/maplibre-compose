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

Public API follows the
[API guidelines](docs/src/content/docs/api-guidelines.md).

In code, use `Maplibre` and treat acronyms and initialisms as words (`Json`,
`Ui`, `Url`). Use PascalCase for constants. Preserve required external API
spellings. In prose, use established spellings such as MapLibre, JSON, and UI.

Use mise tasks: `mise tasks --all` lists them, and `CONTRIBUTING.md` covers
setup outside mise and the demo checks CI cannot run. For work that mise does
not cover, run one named Gradle task per invocation. `./gradlew build` builds
every target, including iOS release frameworks, and can exhaust memory.

- Static checks: `mise run check`; automatic fixes: `mise run fix`. `hk fix`
  rewrites the working tree and does not stage; add `--stage` to stage the
  fixes. The pre-commit hook still stages its own.
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
  composite actions pin each third-party action to a commit SHA with a `#
  version` comment, the same pin everywhere it is used; `mise run
  ci:check-action-pins` checks this. Dependabot updates both `.github/workflows`
  and `.github/actions/*`.

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
reads string extras (`adb shell am start -n
org.maplibre.compose.demoapp/.MainActivity --es route demo/castello-plan`), iOS
takes the same command-line form through `xcrun simctl launch`, and the browser
reads the query string (`/?route=settings/input`).

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

Site pages that show how to use the library import Kotlin examples from `//
#region` blocks in
`demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets/`, which compile
with the demo app, so examples stay correct as the API changes. Add or update a
region for each Kotlin example.

## Issue and PR organization

[Rubberstamp](https://github.com/apps/rubberstamp-bot) labels each new issue and
PR from `.config/rubberstamp.yml`: one kind, at least one `area:*` label, and
`engine:`, `platform:`, `breaking`, `upstream`, and `dependencies` labels when
they apply. Don't label by hand; correct a wrong label instead. Leave native
issue types unset. Usage questions go to Discussions.

## Pull requests

Use draft status for unfinished work, unresolved decisions, or generated code
pending human review. Prefer Conventional Commits for PR titles, which become
the squash-merge commit message.

Write a short PR title and description for a reviewer who will read the diff.
Say what the diff, its KDoc, and CI can't:

- Open with the problem and what users can now do, or what maintainers gain.
- Show one small example of new API, or a bug's trigger with before and after.
- Add these only if they apply: what library users must change, measured numbers
  with a one-line method, manual checks in the demo for UI changes, and affected
  behavior that went unverified.
- Rewrite both when review changes the scope.

When you post AI-contributed GitHub content, including a new Discussion or
Issue, a reply, or a PR description or comment, append this disclosure:
`_AI-assisted — Tool: <tool>; model: <provider>/<model>_`

### CI coverage

Rubberstamp plans which coverage units each PR commit runs, from the units in
the `ci` section of `.config/rubberstamp.yml`. A unit runs when Rubberstamp
judges that the change puts it at risk or when a changed file matches its
`paths`, and a later push is judged only on what changed since the unit last
passed. Hygiene always runs, and pushes to `main` and manual runs run every
unit. The plan job's summary links to the Rubberstamp dashboard with the reason
for each unit.

`ci/jobs.json` assigns each job and variant to a unit, and `ci/plan.py` turns
the plan into the jobs and matrices of `.github/workflows/ci.yml`. A job with
multiple configurations is a matrix over the variants the plan selected.
`all-good` is the required check.

If a plan skips coverage that the change needs, sharpen that unit's `covers`
text or `paths`, and run every unit with `gh workflow run CI --ref <branch>`.
Rubberstamp reads the units from `main`, so a unit that a PR adds or renames
runs only on manual runs until the PR merges.

## Code review rules

Weigh each finding against the priorities above: how likely the problem is in
real use, and what it costs the people it affects.
