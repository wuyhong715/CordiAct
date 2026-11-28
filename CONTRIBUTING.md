# Contributing to CordiAct

CordiAct is being developed as an independent product. Contributions should preserve the boundaries described in `docs/ARCHITECTURE.md`:

- keep provider-specific behavior behind adapters;
- route actions through explicit policy checks;
- do not add credentials, generated APKs, or local machine configuration to Git;
- add tests for core contracts before changing runtime behavior.

Run local JVM checks using the [development guide](docs/development.md). Build Debug, Release, and Beta APKs only in GitHub Actions, never on a local machine.

Before changing runtime boundaries, run `python3 tools/check-architecture-governance.py` and update the [architecture governance baseline](docs/architecture/governance-baseline.md) when module responsibilities or capability status changes. Keep `contract-only`, `poc-only`, and `unavailable` capabilities out of the default product path until their evidence gates pass.
