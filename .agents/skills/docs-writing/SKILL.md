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

### Use plain verbs

Replace phrasal verbs and metaphors with the literal verb.

- Avoid: "Expressions hang off the layer." "Reach for the setter."
- Prefer: "Expressions belong to the layer." "Use the setter."

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

## One mode per page

Each page commits to one of four modes, after [Diátaxis](https://diataxis.fr/).
Serving two modes on one page is the most common structural failure.

| Mode       | Serves                            | Contains                                 |
| ---------- | --------------------------------- | ---------------------------------------- |
| Onboarding | A reader with nothing working yet | The operations every integration needs   |
| Guide      | A reader who knows what they want | One task, start to finish                |
| Concept    | A reader building a mental model  | The model and its consequences, no steps |
| Reference  | A reader looking something up     | Tables, values, complete coverage        |

Explanation inside an onboarding page slows the reader who wants a working
result. Steps inside a concept page make it useless for lookup. Move the
material rather than blending it.

## Headings mark sections, not paragraphs

A heading earns its place when a reader can jump to that section or skip it: a
distinct sub-task, a second mechanism that serves the same need, or a rule that
applies to everything above it. Two or three headings usually cover a page.

A page that walks one task from start to finish stays flat.

Paragraphs before the first heading are the lead, and they carry what holds for
every section below.

Write a heading in sentence case, and name the task rather than the API.

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

- Sentence rules hold throughout.
- Nothing is restated that a link would cover.
- The page serves one mode.
- A guide names the reader's choice before it shows an implementation.
- A guide finishes its task and skips the rest of the domain.
