# Reviewed JVM API snapshots

Approved files use `jvm/<version>/<artifactId>.api`. Each file is canonical
UTF-8 with LF line endings, one final LF, sorted records, and headers that bind
the artifact and baseline version.

The `0.0.1`, `0.0.2`, and `0.0.3` directories are generated from the 53
digest-pinned stable release JARs named by `../jvm-api-baselines.tsv`. Their raw
file SHA-256 values are also pinned in that inventory and verified before a
snapshot is parsed. Never replace them with local SNAPSHOT output or update a
snapshot without updating and independently reviewing all provenance digests.
