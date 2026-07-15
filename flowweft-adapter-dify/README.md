# FlowWeft Dify knowledge-base adapter

This module is the maintained FlowWeft 1.0 projection adapter for the Dify
Knowledge Service API `1.14.x`. It creates documents with
`POST /datasets/{datasetId}/document/create-by-file`, updates them through the
canonical `PATCH /datasets/{datasetId}/documents/{documentId}`, and reads the
exact document and indexing-batch status. It does not use the deprecated
`POST .../update-by-file` alias.

## Required host wiring

Each `DifyKnowledgeBaseProfile` is server-side configuration for exactly one
FlowWeft tenant and one Dify dataset. The host supplies:

- a `DifyApiKeyProvider` backed by its secret manager;
- a durable, transactional `DifyProjectionStore` implementation;
- trusted HTTPS source origins whose URLs FlowWeft may download.

The projection store is mandatory. It must preserve all generations and
implement claim, lease, compare-and-set, idempotency, and `UNKNOWN` fencing
exactly as documented on `DifyProjectionStore`; an in-process map is not a
production substitute. Its key includes the profile's credential-free target
binding digest so a reused profile id cannot carry remote document ids to a
different API authority or dataset. Source download and Dify calls happen after a claim
returns and therefore outside the store transaction.

Private addresses are rejected for the Dify API and source downloads by
default. A self-hosted private Dify endpoint requires the explicit
`allowPrivateApiAddresses` administrator opt-in. Private source origins have a
separate `DifySourceTrustPolicy` opt-in. Redirects and ambient HTTP proxies are
disabled, automatic transport retries are disabled, DNS results are validated before connection, responses and source
files are bounded, and source bytes must match the FlowWeft SHA-256 digest.

The projection lease must be longer than the total connector invocation
timeout. Read-only status calls use bounded retries; create and update calls
are never replayed after an issued request. A timeout, network failure,
malformed success, or rejected issued write is durably fenced as `UNKNOWN`
until exact administrator-authorized reconciliation evidence resolves it.

## Deletion and retrieval limits

Dify `204` means removal was accepted and document `404` means only that the
Service API no longer exposes the document. Neither proves physical erasure of
chunks, vector indexes, replicas, caches, or backups. The adapter therefore
advertises `verifiablePurge=false` and preserves `REMOVAL_ACCEPTED` and
`API_ABSENT` as distinct evidence.

This module does not advertise safe Dify retrieval. Dify Service API keys are
workspace identities rather than FlowWeft end-user ACLs, and the file API does
not write the required projection metadata. Doctor consequently reports
`safeRetrieval=false`; hosts must use FlowWeft's permission-filtered retrieval
contracts and fail closed rather than treating this projection connector as an
authorization boundary.

## Verification

During development, run the focused failing test first and then:

```text
./gradlew :flowweft-adapter-dify:test
```

Before delivery, run `fastCheck`; CNB supplies the affected Java 8 and Java 17
lanes. Mock HTTP contracts do not replace a separately provisioned Dify 1.14
real-environment compatibility result.
