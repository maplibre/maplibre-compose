---
name: docs-writing
description: Writing style and structure for prose in this repository — documentation site pages, contributor docs, KDoc, and repository markdown. Use when writing or editing any prose.
---

# Writing

The sentence rules cover prose everywhere in the repository. The page structure
rules cover everything under `docs/src/content/docs/`.

## Readers

The site serves two audiences.

Users embed a map in a Compose Multiplatform app. Most know Compose well and
know MapLibre barely. Explain MapLibre concepts, and assume Compose knowledge.

Contributors work on this repository and read `CONTRIBUTING.md` and `AGENTS.md`.
Assume they know Kotlin Multiplatform, and explain decisions this project made.

Many readers in both groups read English as a second or third language, so
plainness matters more than rhythm.

## Sentences

Write plain technical English, close to ASD-STE100. Every rule below serves that
goal.

### Name the thing

An indirect "what" or "where" clause describes a thing by the question it
answers. Replace the clause with the noun.

- Avoid: "The camera defines what part of the world the map shows."
- Prefer: "The camera defines the visible part of the map."
- Avoid: "Pass an initial position to set where the map starts."
- Prefer: "Pass an initial position to set the camera position at startup."

### Avoid metaphor

Describe an API with literal verbs. An API does not say, own, carry, or read
over anything. A value is not "yours to define".

- Avoid: "`NotGranted` says which step comes next."
- Prefer: "`NotGranted` reports the next step."
- Avoid: "The metadata bytes are yours to define."
- Prefer: "The library does not interpret the metadata bytes."

### Use plain verbs

Replace phrasal verbs and idioms with the literal verb.

- Avoid: "Expressions hang off the layer." "Reach for the setter."
- Prefer: "Expressions belong to the layer." "Use the setter."

### Use positive wording for guidance

Reserve negative wording for real prohibitions, safety rules, and hard
boundaries.

- Avoid: "Demo screens should not grow into full applications."
- Prefer: "Demo screens stay small and focused."

### State what is true

Describe an absence only when the reader arrives with a specific expectation,
and name that expectation in the same sentence. Everything a library does not do
is otherwise an infinite set.

- Avoid: "There is no callback for this, so read the state you mirror while the
  map is still live."
- Prefer: "Read the state you mirror while the map is live."
- Prefer: "Unlike the Android SDK, this API exposes no map view."

### Put the payload in the main clause

A trailing `which` or `so` clause carries subordinate detail only.

- Avoid: "mise pins every tool, so a task you run locally is the command CI
  runs."
- Prefer: "A task you run locally is the command CI runs. mise pins the tools
  both use."

### Cut the contrast when the positive statement stands alone

- Avoid: "Treat it as work, not as a fixed per-frame slice."
- Prefer: "One call can span an entire style parse. Treat it as variable work."

### End a paragraph on the sentence that matters

Lead with the fact rather than saving a short sentence for emphasis. A closing
fragment reads as significance, so it draws attention by position instead of by
importance.

- Avoid: "Pointers stay valid only until the next frame. Copy what you keep."
- Prefer: "Copy any value you keep, because pointers stay valid only until the
  next frame."

### Describe an API as a thing rather than as a person

- Avoid: "The style refuses to load while a source is missing."
- Prefer: "The style fails to load while a source is missing."

### Keep the syntax explicit

Keep `that` after a verb, keep relative pronouns, and keep articles.

- Avoid: "The camera applies the position the caller set."
- Prefer: "The camera applies the position that the caller set."

### Give each step one instruction

Keep procedural sentences under about twenty words.

## Say it once, and say it plainly

Link to another page instead of copying from it. A copy drifts from its source
and doubles the edit.

Each statement stands on its own, without pointing at an example or at the
current state of the tree.

Cut hedges. "Or equivalent" and vague outcomes leave the reader to guess what
the rule is.

Scope by constraint: general sections state general behavior, and
platform-specific rules belong in clearly labeled subsections.

## The site's page tree

The sidebar has four parts, after [Diátaxis](https://diataxis.fr/):

| Part            | Serves                            | Contains                                 |
| --------------- | --------------------------------- | ---------------------------------------- |
| Getting started | A reader with nothing working yet | The operations every integration needs   |
| Guides          | A reader who knows what they want | One task per page, start to finish       |
| Concepts        | A reader building a mental model  | The model and its consequences, no steps |
| API reference   | A reader looking something up     | Every public symbol, generated from KDoc |

Each page commits to one part. Serving two modes on one page is the most common
structural failure. Explanation inside an onboarding page slows the reader who
wants a working result. Steps inside a concept page make it useless for lookup.
Move the material rather than blending it.

## The site introduces; the API reference completes

The site exists to introduce the library, so every page biases toward brevity.

Leave out implementation details. A reader integrating the library does not need
to know why a mechanism works, only what to do.

Leave out conditions that serve special scenarios. A page that covers the main
path plus every variant reads as a decision tree. Cover the main path, and place
the one or two variants that most integrations meet under their own heading.

Leave out inventories. A table of every value, every artifact, or every
capability is reference material, and the API reference already states it
exactly. Show one representative value and link to the rest.

The test for cutting: if removing the sentence stops no reader from finishing
the task, remove it.

## Code examples compile

Pages pull Kotlin from `// #region` blocks in
`demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets/`, which compile
with the demo app. Add a region to a snippet file rather than writing Kotlin in
the page, so a page cannot show code that no longer builds.

Title a code block with the file that the reader edits. Use the real filename
when the code has one, such as `build.gradle.kts` or `Main.kt`. Use a
representative filename such as `App.kt` when the code lives wherever the
reader's composables live. A block whose destination is not a file, such as a
value pasted into an IDE setting, has no title.

## Headings mark sections, not paragraphs

A heading earns its place when a reader can jump to that section or skip it: a
distinct sub-task, a second mechanism that serves the same need, or a rule that
applies to everything above it. Two or three headings usually cover a page.

A page that walks one task from start to finish stays flat.

Paragraphs before the first heading are the lead, and they carry what holds for
every section below.

Write a heading in sentence case, and name the task or the fact directly. A
heading obeys the sentence rules too: no metaphor, and no indirect clause.

- Avoid: "IDs name what the map renders"
- Prefer: "Layer and source IDs"

## Lead a guide with the decision

A reader arrives with a choice to make, not with a function to call. Name the
choice, give the two or three shapes it takes, and say what each shape costs.
The implementation follows the choice, one section per shape.

Only when there is a fork. A task with one main path leads with that path, and
options that serve special cases follow under their own heading.

Values and flags belong to the decision they serve. A section that lists what a
parameter accepts, before the reader knows why the parameter exists, is
reference material in the wrong place.

Keep the implementation light. The API reference states every behavior exactly,
so a guide draws a rough map: enough of the route to walk it, and the traps that
a reader cannot see from the code.

## A guide covers a task, not an API surface

Finish the task and stop. Leave the rest of the domain alone.

A guide that names every function in an area has become reference. Parameter
semantics and edge-case values belong in the API reference, which Dokka
generates from KDoc.

The test is whether a reader can finish the task, not whether the page mentions
everything.

## Before you finish

- Sentence rules hold throughout: no metaphor, no indirect clauses, plain verbs.
- Nothing is restated that a link would cover.
- The page serves one part of the page tree.
- The page carries no implementation detail, special-case condition, or
  inventory that the API reference covers.
- A guide names the reader's choice before it shows an implementation.
- A guide finishes its task and skips the rest of the domain.
- Kotlin examples come from compiled snippet regions.
