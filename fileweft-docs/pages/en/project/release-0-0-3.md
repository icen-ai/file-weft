---
route: "project/release-0-0-3"
group: "project"
order: 5
locale: "en"
nav: "Release 0.0.3"
title: "Release 0.0.3 contract"
lead: "The current candidate contract adds metadata schemas and review withdrawal while keeping consumption conditional on guarded-tag, protected-main and anonymous remote evidence."
format: "markdown"
---

## What the contract contains

The 0.0.3 line adds Java-friendly metadata schema contracts and runtime validation, safe schema projection and Metadata Doctor checks. It also adds idempotent withdrawal of a pending review and V029 submitter evidence. The release inventory grows to 19 modules and each supported database carries V001–V029. Existing public constructors and SPI boundaries remain compatible; Agent stays compatibility-only and is not exposed by default.

## When the coordinates are consumable

This page describes a candidate contract, not a completed publication. Consume `ai.icen:*:0.0.3` only after the guarded `v0.0.3` tag matches the protected remote `main` HEAD, every required CNB lane for that exact commit succeeds, and an anonymous consumer resolves all 19 coordinates plus the Boot 2, Boot 3, and pure-SPI consumers from a fresh isolated cache. Source, documentation, a tag name, local artifacts, or partial green evidence cannot replace that proof.

## Upgrade boundary

Before V029, stop submit, approve, reject, and withdraw writes; stop old nodes and wait for in-flight review transactions. Apply and validate V029 before starting 0.0.3 nodes. A rollback keeps the V029 column and recorded submitter evidence. The 0.0.2 V001–V028 resources remain immutable.

The source tree and release bundle include the complete contract at `docs/releases/0.0.3.md`. Retain [Release 0.0.2](#/project/release-0-0-2-development) as the previous immutable boundary.
