# JVM API compatibility baselines

This directory contains the reviewed JVM API contract for released `fileweft-*`
artifacts and the additive FlowWeft 1.0 product family. The gate is deliberately
fail-closed: a `pending` provenance or export row is documentation of missing
evidence, not an accepted baseline.

## What runs where

- `verifyLegacyJvmAbi` compares each current legacy JAR with every released
  `0.0.1`, `0.0.2`, and `0.0.3` baseline that contains that artifact. Additions
  are allowed; removals, visibility narrowing, incompatible flag/signature
  changes, and new abstract methods on released interfaces are rejected.
- `verifyFlowWeft10ApiFreeze` requires an exact reviewed 1.0 snapshot and an
  explicit export manifest for each new `flowweft-*` JAR. This also rejects an
  accidental new public class.
- `apiAbiCheck` runs both gates. It is part of
  `releaseArtifactVerification`; it is intentionally not part of `fastCheck`.

## Accepting historical release evidence

1. Obtain the original stable release JARs from an immutable, trusted source.
   Do not use local `*-SNAPSHOT` cache entries.
2. Independently record each exact SHA-256 and verify its release tag/commit.
3. Bind each row to the reviewed JAR SHA-256. The importer emits canonical
   `.api` bytes and their SHA-256; review them before changing the row to
   `ready`. Legacy rows use the literal `NONE` for `exportsSha256` because no
   separate export allow-list existed for those releases.
4. Put the JARs in an isolated Maven-layout repository and run:

   ```powershell
   .\gradlew prepareTrustedJvmApiBaselineImport `
     -PflowweftTrustedBaselineRepository=C:\path\to\trusted-repository
   ```

5. Review `build/reports/jvm-api-trusted-import/`, then copy the approved `.api`
   files into `jvm/<version>/`. The task never modifies checked-in baselines.

The importer verifies path containment, the pinned JAR digest, and JAR manifest
identity before canonicalization. Verification hashes the raw checked-in `.api`
bytes before parsing, so changing both a snapshot and candidate cannot bypass
provenance. Review and acceptance are human actions; generation never promotes
a proposal automatically.

## Accepting the FlowWeft 1.0 freeze

Build the intended release candidate from its exact commit and run
`generateFlowWeft10ApiBaselineProposal` with the intended
`-PfileweftVersion=<version>`. Review every class in `exports/`, every record in
`jvm/1.0.0/`, and the candidate digest report. Copy accepted files into this
directory, bind each inventory row to the exact commit plus JAR, API, and export
manifest SHA-256 values, and set its state to `ready`. Verification hashes all
three raw files before parsing them.

The generated proposal lives only under `build/reports/`. A later API change
requires a fresh proposal and explicit human acceptance; no task overwrites the
reviewed source contract.

Kotlin's raw `Metadata` payload is retained as an explainable snapshot record.
Legacy compatibility uses a stable metadata summary to avoid treating compiler
encoding noise as a source/binary member break. New FlowWeft 1.0 snapshots are
exact, including the raw metadata annotation.
