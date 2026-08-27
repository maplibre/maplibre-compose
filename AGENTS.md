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
  composite actions pin each third-party action to a commit SHA with a
  `# version` comment, the same pin everywhere it is used;
  `mise run ci:check-action-pins` checks this. Dependabot updates both
  `.github/workflows` and `.github/actions/*`.

## Demo app

`demo-app/common` is the demo's only Kotlin Multiplatform module and contains
