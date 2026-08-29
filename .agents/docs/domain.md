# Domain docs

Engineering skills read the repository's domain documentation before exploring
code.

## Sources

- Read `.agents/CONTEXT.md` when it exists.
- Read relevant decisions under `.agents/docs/adr/` when they exist.
- Proceed silently when these files do not exist. Domain-modeling skills create
  them when the work establishes a glossary or architectural decision.

## Layout

This repository uses one domain context:

- `.agents/CONTEXT.md` contains the shared glossary and domain model.
- `.agents/docs/adr/` contains architectural decisions.

These paths override defaults in installed skills. Do not create root
`CONTEXT.md`, `docs/adr/`, or `docs/agents/` paths.

Use terms from `.agents/CONTEXT.md` consistently. Surface any conflict with an
existing ADR instead of silently overriding it.
