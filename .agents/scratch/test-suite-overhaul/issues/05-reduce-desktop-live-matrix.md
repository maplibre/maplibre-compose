# 05: Run the live desktop suite once per backend

**What to build:** Nothing. The live desktop suite stays on every architecture
that packages a backend, including ARM. Those runners have already caught real
bugs that the x64 copy missed.

**Blocked by:** None

**Type:** task

**Status:** wontfix

A later ticket may still add a local `test:desktop-unit` filter so a machine
with no GPU can run layers 0–2. That filter is not a CI matrix cut.
