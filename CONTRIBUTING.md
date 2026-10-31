# Contributing to CordiAct

CordiAct is being developed as an independent product. Contributions should preserve the boundaries described in `docs/ARCHITECTURE.md`:

- keep provider-specific behavior behind adapters;
- route actions through explicit policy checks;
- do not add credentials, generated APKs, or local machine configuration to Git;
- add tests for core contracts before changing runtime behavior.

APK packaging is performed by GitHub Actions, not committed from a local machine.
