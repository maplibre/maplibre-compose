# Make labels explain work across history

**Adopt Stringline’s separation of intent, subject, planning, and progress, then
classify the entire MapLibre Compose archive.** Every issue and PR should have
exactly one kind and at least one semantic area. Add platform, engine,
dependency, compatibility, and upstream qualifiers only when they explain the
work. Keep progress in one GitHub Project, timing in milestones, and
relationships in sub-issues and linked PRs. The recommended catalog has **29
labels**, including the operational `ci:full` label. Shared kind labels should
be authoritative for issues and PRs; native issue types should not become a
second manually maintained taxonomy. This proposal includes historical,
declined, and unmerged work, not just the open backlog.

## Stringline’s useful pattern is separation, not its particular areas

Stringline documents exactly one kind, one or more `area:*` labels, milestones
for timing, Project Status for progress, and native sub-issues for hierarchy.
Its `chore` includes refactors, tooling, and documentation; `research`
identifies a spike or decision. Its transit-specific `feed` kind should stay
there. The important example is the separation of dimensions.
([Stringline guidance](https://github.com/sargunv/stringline/blob/main/AGENTS.md))

Stringline is also an instructive backfill warning: **all 137 open records are
classified, but 134 of 275 PRs remain unlabeled**, mostly from before its
current scheme. Seventeen closed umbrella issues also remain unlabeled. Copying
its forward policy without a completeness target would reproduce that hole.
([Stringline census](https://api.github.com/repos/sargunv/stringline/issues?state=all&per_page=100))

Compose needs that completeness target even more: **1,288 PRs include 1,140
merged, 125 closed unmerged, and 23 open; only 138 currently carry labels**.
Most existing PR labels identify dependencies rather than the feature, defect,
or maintenance concern. The history spans changing title conventions and module
layouts, so a title-prefix or current-directory classifier will miss meaningful
work.
([Compose PR census](https://api.github.com/repos/maplibre/maplibre-compose/pulls?state=all&per_page=100))

Its **406 issues include 25 open and 381 closed; 245 have no labels and 120 lack
a native issue type**. Existing labels mostly capture platforms and temporary
triage: `blocked-upstream` remains on 22 closed issues, and all nine `need info`
issues are closed. This is a classification project, not just label renaming.
([Issue census](https://api.github.com/repos/maplibre/maplibre-compose/issues?state=all&per_page=100))

The research covers complete all-state metadata censuses as of October 11, 2026,
with representative bodies, comments, and PR file lists read closely. It does
not claim that every diff was audited. Stringline’s documented Project policy
was inspected; actual board fields and coverage could not be inspected because
the token lacks `read:project`.

## Give work records one kind and useful semantic areas

Use these five bare kind labels. The kind describes the principal purpose,
independently of Conventional Commit spelling or whether the work shipped.

| Kind       | Meaning                                                                     |
| ---------- | --------------------------------------------------------------------------- |
| `bug`      | Correct behavior that is broken or violates the contract.                   |
| `feature`  | Add or change a capability.                                                 |
| `perf`     | Make runtime behavior faster, lighter, or more efficient.                   |
| `chore`    | Maintain/refactor code, tests, documentation, tooling, or dependencies.     |
| `research` | Answer a question through investigation, design, or a deliberate prototype. |

Usage questions belong in Discussions. Closed support-only issues such as #820
stay in the issue archive without a forced work kind. Record their archive
disposition in the manifest so their history remains accounted for. Open usage
questions belong in Discussions. Classify genuine work by what investigation
established, rather than the initial Bug template: #532 becomes `feature` after
identifying missing transition support. An uncertain feature request remains
`feature` until the deliverable is a spike. Documentation maintenance is `chore`
with `area:docs`; a library defect accompanied by docs remains `bug`.
([#820](https://github.com/maplibre/maplibre-compose/issues/820#issuecomment-5244452877),
[#532](https://github.com/maplibre/maplibre-compose/issues/532#issuecomment-3140557371),
[issue chooser](https://github.com/maplibre/maplibre-compose/blob/main/.github/ISSUE_TEMPLATE/config.yml))

These eleven areas cover recurring library concerns and distinguish product work
from maintainer work without mirroring the source tree.

| Area               | Boundary                                                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `area:map`         | Map lifecycle/state, camera, rendering/presentation, overlays, snapshots, resource hooks, and core platform integration.        |
| `area:style`       | Styles, sources, layers, images, and style-spec properties.                                                                     |
| `area:expressions` | Expression DSL, typing, evaluation, and compiler experiments.                                                                   |
| `area:interaction` | Input, gestures, interaction callbacks, and Compose/Foundation/Material controls.                                               |
| `area:location`    | Location providers, tracking, permissions, and location presentation.                                                           |
| `area:offline`     | Offline downloads, regions, and cache management APIs.                                                                          |
| `area:integration` | Consumers’ setup/dependencies, published artifacts/metadata, linking/loading, host adapters, and platform support requirements. |
| `area:demo`        | Demo behavior, launchers, and presentation.                                                                                     |
| `area:docs`        | User/contributor documentation, site content, and examples maintained for documentation.                                        |
| `area:tooling`     | Maintainer build, CI, release/publishing automation, repository administration, and local development tools.                    |
| `area:benchmarks`  | Benchmark harness, scenarios, results/archive, and code metrics.                                                                |

Use multiple areas when the work has multiple substantive subjects. Do not add
`area:demo` or `area:docs` merely because a public API change updates consumers
and examples. Expression work deserves its own area because it has a distinct,
recurring design history, including the compiler-plugin proposal and prototype.
([Issue #1303](https://github.com/maplibre/maplibre-compose/issues/1303),
[PR #1335](https://github.com/maplibre/maplibre-compose/pull/1335))

Published packaging is product behavior: incorrect JVM metadata is `bug` +
`area:integration`, and a consumer Gradle plugin is `feature` +
`area:integration`. They do not become maintenance chores simply because the
implementation uses Gradle.
([#1183](https://github.com/maplibre/maplibre-compose/issues/1183),
[#1801](https://github.com/maplibre/maplibre-compose/issues/1801))

**Kind labels are the common classification for issues and PRs.** Stop manually
assigning native Bug/Feature/Task types and remove the bug template’s
independent type assignment when adopting the scheme. Clear the redundant native
types during backfill after exporting them as evidence. This changes repository
usage, not MapLibre’s organization-wide type definitions. The existing template
currently assigns a native bug type, so merely adding kind labels would leave
two competing systems.
([Bug template](https://github.com/maplibre/maplibre-compose/blob/main/.github/ISSUE_TEMPLATE/bug-report.yml))

## Keep impact qualifiers sparse and move planning out of labels

The remaining thirteen labels have specific purposes.

| Dimension           | Labels                                                                                                                         | Rule                                                                                                                                                                                                                                                                                           |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Engine              | `engine:native`, `engine:gl-js`                                                                                                | Add for engine-specific behavior or implementation. Native means the MapLibre Native backend, including its JVM hosts; it does not mean Kotlin/Native alone.                                                                                                                                   |
| Platform            | `platform:android`, `platform:ios`, `platform:web`, `platform:desktop`, `platform:linux`, `platform:macos`, `platform:windows` | Add for a meaningful platform restriction, reproduction, or implementation. Ordinary shared work does not receive every platform.                                                                                                                                                              |
| Durable modifiers   | `dependencies`, `breaking`, `upstream`                                                                                         | Dependency upgrades are a subject modifier, not automatically `chore`. Breaking covers caller migration, source/binary compatibility, integration contracts, and support floors. Upstream identifies an evidenced external defect/capability limitation; require a link or concrete diagnosis. |
| Operational control | `ci:full`                                                                                                                      | Preserve its exact name and CI meaning; never infer it during semantic backfill.                                                                                                                                                                                                               |

GitHub labels have no inheritance. For an OS-specific JVM desktop defect, apply
both `platform:desktop` and the OS label, so desktop searches include it. For
the native macOS launcher alone, use `platform:macos`; do not imply JVM desktop
impact. Apply `engine:native` only when the backend matters, rather than to
every Android, iOS, or desktop record.

`upstream` records provenance, not current progress. Retain it on a closed
workaround such as iOS text rendering #551; external causes can include Compose
or Skiko as well as MapLibre. Do not add it merely because the responsible layer
is uncertain.
([#551](https://github.com/maplibre/maplibre-compose/issues/551#issuecomment-4323600464),
[Skiko compatibility #1312](https://github.com/maplibre/maplibre-compose/issues/1312))

Create one Compose Project for active planning. Give it Status values **Backlog,
Ready, In progress, In review, Blocked, Waiting on reporter, Done**, with a
blocker link/reason when blocked. Priority belongs in a Project field when
needed. Use area/kind filters for library work, maintenance, and research; do
not create separate boards merely for those labels. Explicitly represent
upstream blockers with dependency links, rather than maintaining both a blocked
label and Project Status. Stringline’s old feed-status labels disappeared when
Project organization replaced its trackers.
([Tracker closure](https://github.com/sargunv/stringline/issues/91#issuecomment-6051600979),
[current policy](https://github.com/sargunv/stringline/blob/main/AGENTS.md))

Use milestones for intended releases or initiatives with concrete exit criteria;
preserve the meaning of the existing 17 closed milestones, which mix releases
and workstreams. Native sub-issues group implementation tasks under an outcome,
without an `epic` label. Keep merged status, issue state/reason, and closing
explanations authoritative for delivery and disposition. Never equate “closed”
with “shipped”: #908 closed with an alternative, not implementation of the
requested dedicated API. Historical release membership comes from tags and
release evidence, not merge dates. Add active work to the Project; labels
provide archive searches without crowding the board.
([Existing milestones](https://github.com/maplibre/maplibre-compose/milestones?state=closed),
[#908 resolution](https://github.com/maplibre/maplibre-compose/issues/908#issuecomment-5522072832))

## Backfill intent faithfully, including work that never shipped

These examples show the distinctions the backfill must preserve.

| Historical record                                                                                                         | Recommended classification                                                                                                                       |
| ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| [PR #1530, Android Studio previews](https://github.com/maplibre/maplibre-compose/pull/1530)                               | `bug`, `area:map`, `platform:android`: the apparently additive title fixes a preview crash.                                                      |
| [PR #1492, Native FFI upgrade](https://github.com/maplibre/maplibre-compose/pull/1492)                                    | `perf`, `area:map`, `area:style`, `dependencies`, `engine:native`, `breaking`: deliberate performance work also raises the iOS support floor.    |
| [PR #1643, source maxzoom default](https://github.com/maplibre/maplibre-compose/pull/1643)                                | `bug`, `area:style`, `breaking`: correcting defaults can still require migration.                                                                |
| [PR #1753, camera target → center](https://github.com/maplibre/maplibre-compose/pull/1753)                                | `chore`, `area:map`, `breaking`: API refactoring does not need a separate refactor kind.                                                         |
| [PR #1556, historical benchmark tooling](https://github.com/maplibre/maplibre-compose/pull/1556)                          | `chore`, `area:benchmarks`: measuring performance is not itself runtime performance work.                                                        |
| [PR #1769, Linux Vulkan prototype](https://github.com/maplibre/maplibre-compose/pull/1769)                                | `research`, `area:map`, `engine:native`, `platform:desktop`, `platform:linux`; its unmerged state records the outcome.                           |
| [PR #1335, expression compiler prototype](https://github.com/maplibre/maplibre-compose/pull/1335#issuecomment-5575880238) | `research`, `area:expressions`; do not mark its linked feature issue delivered.                                                                  |
| [PR #243, unfinished JS bindings](https://github.com/maplibre/maplibre-compose/pull/243#issuecomment-3244538047)          | `feature`, `area:style`, `engine:gl-js`, `platform:web`: withdrawn implementation is not automatically research.                                 |
| [Issue #239, historical Linux scaling](https://github.com/maplibre/maplibre-compose/issues/239#issuecomment-3241237546)   | `bug`, `area:map`, `platform:desktop`, `platform:linux`, `engine:gl-js`: this desktop implementation used a webview, not today’s Native backend. |

Migrate the entire old catalog explicitly:

| Current label                                                   | Migration                                                                                                                                                                                               |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `android`, `ios`, `desktop`, `web`, `linux`, `macos`, `windows` | Rename to corresponding `platform:*`; audit OS/desktop pairing and historical scope.                                                                                                                    |
| `maplibre-native`                                               | Rename to `engine:native`.                                                                                                                                                                              |
| `javascript`                                                    | Retire; review each assignment. Use `engine:gl-js` for engine work, `platform:web` for browser impact, or `area:tooling` + `dependencies` for build dependency work. Language alone is insufficient.    |
| `documentation`                                                 | Rename to `area:docs`; assign kind from intent.                                                                                                                                                         |
| `infra`                                                         | Replace with `area:tooling`; use `area:integration` for consumer-facing packaging/setup. Assign kind from intent.                                                                                       |
| `exploratory`                                                   | Retire; classify actual spikes as `research`, uncertain implementation requests as `feature`.                                                                                                           |
| `blocked-upstream`, `need info`                                 | Retire after moving active records into Project Status with blocker/reporter context. Assign durable `upstream` only where external-cause evidence supports it; preserve old assignments in the export. |
| `dependencies`, `ci:full`                                       | Keep exactly; audit dependency scope but preserve existing CI decisions.                                                                                                                                |

First export every issue/PR, label assignment, native type, milestone, state,
and relevant closure context. Produce a dry-run manifest containing proposed
labels or a retained closed-support disposition, evidence, confidence, and
unresolved questions for **every record**. Existing native types and labels are
hints; inspect titles and bodies, linked work, comments, and changed files where
ambiguous. Map historical paths to stable concepts, including deleted
components. Classify PRs by their own work rather than blindly copying a linked
issue’s broader areas.

Automate high-confidence suggestions; review the rest and sample each historical
era. Routine Dependabot upgrades usually become `chore` + `dependencies` +
`area:tooling`, but engine upgrades can concern library areas or be purposeful
`perf`/`bug` work. Bot authorship alone is never classification: `sargunv-bot`
also authored features and fixes.
([Feature #1071](https://github.com/maplibre/maplibre-compose/pull/1071),
[fix #1090](https://github.com/maplibre/maplibre-compose/pull/1090))

Apply approved assignments in batches, preserve a rollback export, and verify
**all 1,694 original records are accounted for**: every retained issue and PR
has one kind and at least one area, and closed support-only conversations have
an explicit retained-archive disposition. Check open issues, closed issues, open
PRs, merged PRs, and closed-unmerged PRs separately. Ambiguity stays explicit in
the manifest until resolved, not an `unknown` label or forced guess. Verify
breaking candidates beyond title punctuation: the census contains 69 explicit
title signals, but older API migrations and support-floor changes require
body/API review.

Update repository guidance, the bug template, and contributor search links with
the scheme. Dependabot already emits `dependencies`, which remains valid.
Preserve `ci:full`: the planner consumes it directly, and unrelated label
changes can invoke CI because ready/full workflows subscribe to labeling events.
Relabel open PRs in controlled batches and inspect resulting checks; do not
assume taxonomy migration is operationally inert.
([Dependabot configuration](https://github.com/maplibre/maplibre-compose/blob/main/.github/dependabot.yml),
[CI planner](https://github.com/maplibre/maplibre-compose/blob/main/ci/plan.py),
[ready workflow](https://github.com/maplibre/maplibre-compose/blob/main/.github/workflows/ci-ready.yml),
[full workflow](https://github.com/maplibre/maplibre-compose/blob/main/.github/workflows/ci-full.yml))

## The archive should answer the same questions as the backlog

The useful test is whether someone can find all camera fixes, expression
experiments, browser limitations, or compatibility changes across the
repository’s lifetime. Stable semantic labels make those searches reliable;
state, releases, and planning fields explain what happened and what happens
next. The backfill’s reviewed manifest is therefore part of the work, not a
disposable script: it records the historical interpretations that labels alone
cannot explain.

## Approved application

The user approved the scheme, kept closed support conversations in the issue
archive, and kept #288 as a bug. All classification choices are resolved. The
application covers the 1,694-record snapshot and seven subsequently opened PRs,
whose descriptions and full file lists were reviewed under the same rules. Save
live labels and issue types before mutation and verify the complete census
afterward. Preserve `ci:full` and all state, milestone, assignee, and
relationship metadata.

Project access is unavailable with the current token. Preserve the ten active
Blocked status suggestions in the application package for a later Project
update; do not claim that a Project was created or changed.
