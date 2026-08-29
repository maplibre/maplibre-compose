# Domain docs

Engineering skills read the repository's domain documentation before exploring
code.

## Sources

- Read `CONTEXT.md` at the repository root when it exists.
- Read relevant decisions under `docs/adr/` when they exist.
- Proceed silently when these files do not exist. Domain-modeling skills create
  them when the work establishes a glossary or architectural decision.

## Layout

This repository uses one domain context:

- `CONTEXT.md` contains the shared glossary and domain model.
- `docs/adr/` contains architectural decisions.

Use terms from `CONTEXT.md` consistently. Surface any conflict with an existing
ADR instead of silently overriding it.
