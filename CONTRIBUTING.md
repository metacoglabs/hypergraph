# Contributing to HStore

Thank you for helping. This document covers how changes get into the tree. The toolchain, repository
layout, conventions and recipes for common changes are in [docs/development.md](docs/development.md).

## Before you start

* For anything larger than a bug fix, open an issue first and describe the problem and your proposed approach.
  Changes to the on-disk format, the WAL, recovery or the wire protocol need a design discussion before code.
* Search existing issues and pull requests to avoid duplicate work.

## Workflow

1. Fork the repository and create a topic branch from `main`.
2. Make the change with tests. Every bug fix needs a test that fails without the fix.
3. Run the full build: `./mvnw install`. It compiles with `-Werror` and runs every test suite, including the
   crash-recovery matrix.
4. If you touched Studio, check the modules with `node --check` and test the change in a browser in both the
   light and the dark theme.
5. If you touched packaging, build the native image (`./mvnw -Pnative -DskipTests package -pl server`) and,
   if possible, the Docker image.
6. Update the documentation in `docs/` when behaviour, settings, log messages, HQL syntax or the on-disk
   format change.
7. Open a pull request that explains *what* changed and *why*, and how you verified it.

CI only runs once a pull request is merged into `main`, so nothing checks your branch before review. Run the
full build locally before opening the pull request.

## Standards

* Code carries no comments. Choose names and structure that explain themselves, and put design rationale in
  `docs/`.
* Use modern Java: records, sealed hierarchies, pattern matching, virtual threads. Code must stay compatible
  with GraalVM native image: no reflection or runtime code generation.
* Persisted hashes and fingerprints must be deterministic across JVM runs and native images. Never derive them
  from `hashCode()` of enums, records or arbitrary objects.
* Durability-sensitive changes (commit pipeline, WAL, checkpoint, recovery, compaction) must keep
  `CrashRecoveryTest` green and add crash points when they add durability steps.
* Benchmarks claims in pull requests should come from `benchmarks/run.sh` output, quoting the environment
  line it prints.

## Commit messages

Use an imperative summary line of at most 72 characters (`Fix leapfrog seek past the last key`), a blank
line, then a body explaining the motivation and any trade-offs. Reference issues with `Fixes #123` where it
applies.

## Reporting bugs

Include:

* the `hstore version` output, the platform, and whether you used the Docker image, the native binary or the JVM build;
* the HQL or API calls that reproduce the problem, ideally against `examples/clinical-claims.hql`;
* relevant server log lines (run with `log_level = debug` if needed);
* for storage problems, the output of `hstore check <dir>` on a stopped copy of the data directory.

Report security vulnerabilities privately to the maintainers rather than in a public issue.

## License

By contributing you agree that your contributions are licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).
