import assert from "node:assert/strict";
import test from "node:test";

import {
  SecurityGateError,
  createErrorSummary,
  enforceVerifiedTlsEnvironment,
  extractRuntimeComponents,
  parseNpmPurl,
  runGate,
  scoreCvssSeverity,
  validateVex,
} from "../scripts/verify-osv-release.mjs";

const NOW = new Date("2026-07-15T00:00:00Z");
const EMPTY_VEX = Object.freeze({ schemaVersion: 1, exceptions: [] });
const JACKSON_PURL = "pkg:maven/com.fasterxml.jackson.core/jackson-databind";
const JACKSON_ADVISORY = "GHSA-5jmj-h7xm-6q6v";

test("the CLI removes inherited Node TLS verification bypasses", () => {
  const environment = {
    NODE_TLS_REJECT_UNAUTHORIZED: "0",
    NODE_EXTRA_CA_CERTS: "/reviewed/private-ca.pem",
  };
  enforceVerifiedTlsEnvironment(environment);
  assert.equal(environment.NODE_TLS_REJECT_UNAUTHORIZED, undefined);
  assert.equal(environment.NODE_EXTRA_CA_CERTS, "/reviewed/private-ca.pem");
});

function bom(...components) {
  return {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    components,
  };
}

function mavenComponent(group, name, version, qualifiers = "?type=jar") {
  return {
    type: "library",
    group,
    name,
    version,
    purl: `pkg:maven/${group}/${name}@${version}${qualifiers}`,
  };
}

function npmComponent(name, version) {
  const encodedName = name.startsWith("@") ? `%40${name.slice(1)}` : name;
  return {
    type: "library",
    name,
    version,
    purl: `pkg:npm/${encodedName}@${version}`,
  };
}

function vulnerability(id, vector, packagePurl = null, ecosystem = "Maven", packageName = "fixture:library") {
  const severity = vector === null ? undefined : [{ type: "CVSS_V3", score: vector }];
  return {
    id,
    modified: "2026-07-14T00:00:00Z",
    ...(severity === undefined ? {} : { severity }),
    ...(packagePurl === null
      ? {}
      : {
        affected: [{
          package: { ecosystem, name: packageName, purl: packagePurl },
        }],
      }),
  };
}

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function createOsvMock({ matches = new Map(), details = new Map() } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url.endsWith("/v1/querybatch")) {
      const request = JSON.parse(init.body);
      return jsonResponse({
        results: request.queries.map((query) => ({
          vulns: (matches.get(query.package.purl) ?? []).map((id) => ({
            id,
            modified: "2026-07-14T00:00:00Z",
          })),
        })),
      });
    }
    const marker = "/v1/vulns/";
    const index = url.indexOf(marker);
    assert.notEqual(index, -1, `unexpected URL ${url}`);
    const id = decodeURIComponent(url.slice(index + marker.length));
    assert.ok(details.has(id), `missing detail fixture for ${id}`);
    return jsonResponse(details.get(id));
  };
  return { calls, fetchImpl };
}

function exactJacksonVex(overrides = {}) {
  return {
    schemaVersion: 1,
    exceptions: [{
      advisory: JACKSON_ADVISORY,
      purl: JACKSON_PURL,
      version: "2.21.5",
      scope: "runtime-closure",
      evidenceUrl:
        "https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-5jmj-h7xm-6q6v",
      owner: "FlowWeft Security",
      expiresAt: "2026-08-15T00:00:00Z",
      ...overrides,
    }],
  };
}

test("runtime Maven and npm components are both queried while unsupported ecosystems are counted", async () => {
  const component = mavenComponent("fixture", "library", "1.0.0");
  const browserComponent = npmComponent("browser-only", "1.0.0");
  const id = "GHSA-aaaa-bbbb-cccc";
  const osv = createOsvMock({
    matches: new Map([["pkg:maven/fixture/library@1.0.0", [id]]]),
    details: new Map([[id, vulnerability(
      id,
      "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
    )]]),
  });
  const summary = await runGate({
    bom: bom(component, browserComponent, {
      type: "library",
      name: "python-only",
      version: "1.0.0",
      purl: "pkg:pypi/python-only@1.0.0",
    }),
    vex: EMPTY_VEX,
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });

  assert.equal(summary.status, "blocked");
  assert.equal(summary.counts.blockingMatches, 1);
  assert.equal(summary.counts.mavenComponents, 1);
  assert.equal(summary.counts.npmComponents, 1);
  assert.equal(summary.counts.ignoredOtherComponents, 1);
  assert.equal(summary.blockingFindings[0].severity, "CRITICAL");
  assert.equal(summary.blockingFindings[0].cvssScore, 9.8);
  const batchBody = JSON.parse(osv.calls[0].init.body);
  assert.deepEqual(batchBody.queries, [
    { package: { purl: "pkg:maven/fixture/library@1.0.0" } },
    { package: { purl: "pkg:npm/browser-only@1.0.0" } },
  ]);
});

test("canonical scoped npm purls are parsed and inconsistent or ambiguous identities fail closed", async () => {
  assert.deepEqual(
    parseNpmPurl("pkg:npm/%40next/swc-win32-x64-msvc@16.2.10"),
    {
      ecosystem: "npm",
      namespace: "@next",
      name: "swc-win32-x64-msvc",
      osvName: "@next/swc-win32-x64-msvc",
      packagePurl: "pkg:npm/%40next/swc-win32-x64-msvc",
      version: "16.2.10",
      versionedPurl: "pkg:npm/%40next/swc-win32-x64-msvc@16.2.10",
    },
  );
  const extracted = extractRuntimeComponents(bom(
    npmComponent("@next/swc-win32-x64-msvc", "16.2.10"),
  ));
  assert.equal(extracted.npmComponents, 1);
  assert.equal(extracted.mavenComponents, 0);

  for (const invalid of [
    npmComponent("@next/swc-win32-x64-msvc", "16.2.10"),
    { ...npmComponent("@next/swc-win32-x64-msvc", "16.2.10"), version: "16.2.9" },
    { ...npmComponent("@next/swc-win32-x64-msvc", "16.2.10"), name: "@next/other" },
  ]) {
    if (invalid.name === "@next/swc-win32-x64-msvc" && invalid.version === "16.2.10") {
      invalid.purl = "pkg:npm/%2540next/swc-win32-x64-msvc@16.2.10";
    }
    assert.throws(
      () => extractRuntimeComponents(bom(invalid)),
      (error) => error instanceof SecurityGateError && error.code === "BOM_COMPONENT_INVALID",
    );
  }
});

test("npm affected-package severity is matched without relying on Maven coordinates", async () => {
  const component = npmComponent("@scope/library", "3.2.1");
  const purl = "pkg:npm/%40scope/library";
  const id = "GHSA-npm1-npm2-npm3";
  const osv = createOsvMock({
    matches: new Map([[`${purl}@3.2.1`, [id]]]),
    details: new Map([[id, vulnerability(
      id,
      "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
      purl,
      "npm",
      "@scope/library",
    )]]),
  });
  const summary = await runGate({
    bom: bom(component),
    vex: EMPTY_VEX,
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });
  assert.equal(summary.status, "blocked");
  assert.equal(summary.counts.npmComponents, 1);
  assert.equal(summary.blockingFindings[0].purl, purl);
});

test("runtime Low/Medium findings do not block and the threshold is not a finding-count budget", async () => {
  const component = mavenComponent("fixture", "library", "1.0.0");
  const ids = ["GHSA-1111-2222-3333", "GHSA-4444-5555-6666"];
  const moderate = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N";
  const osv = createOsvMock({
    matches: new Map([["pkg:maven/fixture/library@1.0.0", ids]]),
    details: new Map(ids.map((id) => [id, vulnerability(id, moderate)])),
  });
  const summary = await runGate({
    bom: bom(component),
    vex: EMPTY_VEX,
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });

  assert.equal(summary.status, "pass");
  assert.equal(summary.counts.osvMatches, 2);
  assert.equal(summary.counts.belowThresholdMatches, 2);
  assert.equal(summary.counts.blockingMatches, 0);
});

test("OSV HTTP failures retry within a fixed bound and then fail closed", async () => {
  let requests = 0;
  const fetchImpl = async () => {
    requests += 1;
    return jsonResponse({ unavailable: true }, 503);
  };
  await assert.rejects(
    runGate({
      bom: bom(mavenComponent("fixture", "library", "1.0.0")),
      vex: EMPTY_VEX,
      fetchImpl,
      now: NOW,
      sleepImpl: async () => {},
    }),
    (error) => error instanceof SecurityGateError && error.code === "OSV_UNAVAILABLE",
  );
  assert.equal(requests, 3);
});

test("OSV requests include response-body timeouts and fail closed without a live network", async () => {
  let requests = 0;
  const fetchImpl = async (_url, init) => {
    requests += 1;
    const body = new ReadableStream({
      start(controller) {
        init.signal.addEventListener("abort", () => {
          const error = new Error("fixture body timeout");
          error.name = "AbortError";
          controller.error(error);
        }, { once: true });
      },
    });
    return new Response(body, {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };
  await assert.rejects(
    runGate({
      bom: bom(mavenComponent("fixture", "library", "1.0.0")),
      vex: EMPTY_VEX,
      fetchImpl,
      now: NOW,
      sleepImpl: async () => {},
      testHttpPolicy: {
        attempts: 2,
        timeoutMilliseconds: 5,
        retryDelayMilliseconds: [0],
      },
    }),
    (error) => error instanceof SecurityGateError && error.code === "OSV_UNAVAILABLE",
  );
  assert.equal(requests, 2);
});

test("malicious or internally inconsistent CycloneDX components fail before network access", async () => {
  let requested = false;
  const fetchImpl = async () => {
    requested = true;
    throw new Error("must not run");
  };
  const maliciousBom = bom({
    type: "library",
    version: "2.0.0",
    purl: "pkg:maven/fixture/library@1.0.0?token=SHOULD_NOT_APPEAR",
  });
  let caught;
  try {
    await runGate({ bom: maliciousBom, vex: EMPTY_VEX, fetchImpl, now: NOW });
  } catch (error) {
    caught = error;
  }
  assert.ok(caught instanceof SecurityGateError);
  assert.equal(caught.code, "BOM_COMPONENT_INVALID");
  assert.equal(requested, false);
  assert.doesNotMatch(JSON.stringify(createErrorSummary(caught)), /SHOULD_NOT_APPEAR/u);
});

test("expired VEX entries fail validation", () => {
  assert.throws(
    () => validateVex(exactJacksonVex({ expiresAt: "2026-07-14T23:59:59Z" }), { now: NOW }),
    (error) => error instanceof SecurityGateError && error.code === "VEX_EXPIRED",
  );
});

test("a VEX entry for the wrong component version fails before OSV is queried", async () => {
  let requested = false;
  await assert.rejects(
    runGate({
      bom: bom(mavenComponent("com.fasterxml.jackson.core", "jackson-databind", "2.21.5")),
      vex: exactJacksonVex({ version: "2.21.4" }),
      fetchImpl: async () => {
        requested = true;
        throw new Error("must not run");
      },
      now: NOW,
    }),
    (error) => error instanceof SecurityGateError && error.code === "VEX_TARGET_NOT_IN_BOM",
  );
  assert.equal(requested, false);
});

test("broad, duplicate, and unknown-field VEX entries are rejected", () => {
  assert.throws(
    () => validateVex(exactJacksonVex({ purl: "pkg:maven/com.fasterxml.jackson.core/*" }), { now: NOW }),
    (error) => error instanceof SecurityGateError && error.code === "VEX_SCHEMA_INVALID",
  );
  const duplicate = exactJacksonVex();
  duplicate.exceptions.push({ ...duplicate.exceptions[0] });
  assert.throws(
    () => validateVex(duplicate, { now: NOW }),
    (error) => error instanceof SecurityGateError && error.code === "VEX_SCHEMA_INVALID",
  );
  const unknown = exactJacksonVex();
  unknown.exceptions[0].reason = "trust me";
  assert.throws(
    () => validateVex(unknown, { now: NOW }),
    (error) => error instanceof SecurityGateError && error.code === "VEX_SCHEMA_INVALID",
  );
});

test("the exact Jackson 2.21.5 maintainer advisory VEX waives only its bound runtime match", async () => {
  const component = mavenComponent("com.fasterxml.jackson.core", "jackson-databind", "2.21.5");
  const osv = createOsvMock({
    matches: new Map([[`${JACKSON_PURL}@2.21.5`, [JACKSON_ADVISORY]]]),
    details: new Map([[JACKSON_ADVISORY, {
      ...vulnerability(
        JACKSON_ADVISORY,
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
      ),
      aliases: ["CVE-2026-54515"],
    }]]),
  });
  const summary = await runGate({
    bom: bom(component),
    vex: exactJacksonVex(),
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });

  assert.equal(summary.status, "pass");
  assert.equal(summary.counts.exemptedMatches, 1);
  assert.equal(summary.vex.matchedEntries, 1);
  assert.equal(summary.vex.nearestExpiry, "2026-08-15T00:00:00Z");
});

test("missing or CVSS v4-only scores block as unknown instead of becoming an authorization downgrade", async () => {
  const component = mavenComponent("fixture", "library", "1.0.0");
  const id = "GHSA-abcd-1234-efgh";
  const osv = createOsvMock({
    matches: new Map([["pkg:maven/fixture/library@1.0.0", [id]]]),
    details: new Map([[id, vulnerability(id, null)]]),
  });
  const summary = await runGate({
    bom: bom(component),
    vex: EMPTY_VEX,
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });
  assert.equal(summary.status, "blocked");
  assert.equal(summary.blockingFindings[0].severity, "UNKNOWN");
  assert.equal(summary.blockingFindings[0].reason, "NO_SUPPORTED_CVSS_SCORE");
});

test("mixed CVSS v3 and unsupported v4 cannot use the lower legacy score as a downgrade", () => {
  assert.deepEqual(
    scoreCvssSeverity([
      { type: "CVSS_V3", score: "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N" },
      { type: "CVSS_V4", score: "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N" },
    ]),
    { score: null, cvssVersion: "4.0-unsupported" },
  );
  assert.throws(
    () => scoreCvssSeverity([{ type: "VENDOR", score: "critical" }]),
    (error) => error instanceof SecurityGateError && error.code === "OSV_RESPONSE_INVALID",
  );
});

test("withdrawn timestamps are validated and future withdrawal does not suppress an active finding", async () => {
  const component = mavenComponent("fixture", "library", "1.0.0");
  const id = "GHSA-future-with-draw";
  const detail = {
    ...vulnerability(id, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"),
    withdrawn: "2026-07-16T00:00:00Z",
  };
  const osv = createOsvMock({
    matches: new Map([["pkg:maven/fixture/library@1.0.0", [id]]]),
    details: new Map([[id, detail]]),
  });
  const summary = await runGate({
    bom: bom(component),
    vex: EMPTY_VEX,
    fetchImpl: osv.fetchImpl,
    now: NOW,
  });
  assert.equal(summary.status, "blocked");
  assert.equal(summary.counts.withdrawnMatches, 0);

  const malformed = createOsvMock({
    matches: new Map([["pkg:maven/fixture/library@1.0.0", [id]]]),
    details: new Map([[id, { ...detail, withdrawn: "not-a-timestamp" }]]),
  });
  await assert.rejects(
    runGate({
      bom: bom(component),
      vex: EMPTY_VEX,
      fetchImpl: malformed.fetchImpl,
      now: NOW,
    }),
    (error) => error instanceof SecurityGateError && error.code === "OSV_RESPONSE_INVALID",
  );
});

test("CVSS v2/v3 calculations use the highest official base score", () => {
  assert.deepEqual(
    scoreCvssSeverity([
      { type: "CVSS_V3", score: "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N" },
      { type: "CVSS_V3", score: "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H" },
    ]),
    { score: 9.8, cvssVersion: "3.1" },
  );
  assert.deepEqual(
    scoreCvssSeverity([
      { type: "CVSS_V2", score: "AV:N/AC:L/Au:N/C:C/I:C/A:C" },
    ]),
    { score: 10, cvssVersion: "2.0" },
  );
});

test("public error summaries are stable and never include thrown headers, environment data, or response text", async () => {
  const secret = "Authorization: Bearer TOP-SECRET";
  let caught;
  try {
    await runGate({
      bom: bom(mavenComponent("fixture", "library", "1.0.0")),
      vex: EMPTY_VEX,
      fetchImpl: async () => {
        throw new Error(secret);
      },
      now: NOW,
      sleepImpl: async () => {},
    });
  } catch (error) {
    caught = error;
  }
  const first = JSON.stringify(createErrorSummary(caught));
  const second = JSON.stringify(createErrorSummary(caught));
  assert.equal(first, second);
  assert.doesNotMatch(first, /TOP-SECRET|Authorization|Bearer/u);
  assert.match(first, /OSV_UNAVAILABLE/u);
});
