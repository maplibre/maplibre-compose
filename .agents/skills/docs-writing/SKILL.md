---
name: docs-writing
description: Write or edit repository documentation, KDoc, contributor guidance, and PR descriptions. Covers this project's audience, documentation structure, and compiled examples.
---

# Writing

Write plain technical English for readers who may use English as a second
language. Library users usually know Compose but need MapLibre concepts
explained. Contributors know Kotlin Multiplatform and need the reasons for
project-specific decisions.

Lead with the fact, action, or decision the reader needs. Prefer concrete nouns,
direct verbs, and explicit conditions. Avoid promotional language, stock
transitions, and metaphors that obscure API behavior. Keep enough detail to
explain limitations and tradeoffs; brevity should not hide them.

Use paragraphs for explanations, lists for steps or parallel items, and tables
for comparisons. Headings use sentence case and identify sections that readers
can navigate. Link to existing explanations instead of repeating them.

## Documentation site

Pages under `docs/src/content/docs/` follow the site's Diátaxis structure:

- Getting started helps a new user integrate a working map.
- Guides complete a specific task and explain choices where they arise.
- Concepts explain behavior and design.
- API reference comes from KDoc and defines the public API contract.

Choose the page's primary purpose and keep it focused. Introduce the common path
on the site and link to API details. Include platform differences, conditions,
and implementation details when they affect the reader's decision or ability to
complete the task.

Pages import Kotlin examples from `// #region` blocks in
`demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets/`, which compile
with the demo app. Add or update a snippet region instead of embedding an
untested Kotlin example in a page. Title file-oriented code blocks with the
destination filename, such as `build.gradle.kts` or `App.kt`.

## KDoc and pull requests

KDoc defines behavior, parameter semantics, lifecycle requirements, and platform
limitations that callers need. Keep those contracts precise even when they
require more explanation than a site guide.

PR descriptions follow `.github/PULL_REQUEST_TEMPLATE.md` and `AI_POLICY.md`.
Lead with the problem and resulting behavior, then summarize validation and
material limitations. Scale the detail to the change. Omit session history,
file-by-file recaps, and claims that exceed the evidence.
