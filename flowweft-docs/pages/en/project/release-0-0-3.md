---
route: "project/release-0-0-3"
group: "project"
order: 5
locale: "en"
nav: "Release 0.0.3"
title: "Release 0.0.3 notes"
lead: "The stable release adds metadata schemas and review withdrawal; the exact tag, 12/12 CNB lanes, and 19/19 anonymous artifact readback are complete."
format: "markdown"
---

## What the release contains

The 0.0.3 line adds Java-friendly metadata schema contracts and runtime validation, safe schema projection and Metadata Doctor checks. It also adds idempotent withdrawal of a pending review and V029 submitter evidence. The release inventory grows to 19 modules and each supported database carries V001–V029. Existing public constructors and SPI boundaries remain compatible; Agent stays compatibility-only and is not exposed by default.

## Stable publication evidence

`v0.0.3` was published as a stable release on 2026-07-14 at commit `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`. CNB `tag_push` build `cnb-cl8-1jtgih45j` completed 12/12 pipelines with zero failures; the publisher verified identity, uploaded 19 modules, destroyed its publication token, and ran the cold-cache consumer. Independent anonymous readback also verified the POM, main JAR, and `.sha256` for all 19 coordinates, so `ai.icen:*:0.0.3` is now stable and consumable.

## Upgrade boundary

Before V029, stop submit, approve, reject, and withdraw writes; stop old nodes and wait for in-flight review transactions. Apply and validate V029 before starting 0.0.3 nodes. A rollback keeps the V029 column and recorded submitter evidence. The 0.0.2 V001–V028 resources remain immutable.

The source tree and release bundle include the complete notes at `docs/releases/0.0.3.md`. The public [CNB Release](https://cnb.cool/china.ai/file-weft/-/releases/tag/v0.0.3) records the release page. Retain [Release 0.0.2](#/project/release-0-0-2-development) as the previous immutable boundary.
