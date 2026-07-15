#!/usr/bin/env node

import { lstat, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const GATE_NAME = "flowweft-osv-runtime-closure";
const OSV_BASE_URL = "https://api.osv.dev";
const RUNTIME_SCOPE = "runtime-closure";
const BLOCKING_CVSS_SCORE = 7.0;

const LIMITS = Object.freeze({
  bomBytes: 16 * 1024 * 1024,
  vexBytes: 256 * 1024,
  responseBytes: 4 * 1024 * 1024,
  components: 4_096,
  vexEntries: 64,
  batchSize: 128,
  batchPages: 4,
  vulnerabilityIds: 200,
  httpRequests: 256,
});

const HTTP_POLICY = Object.freeze({
  attempts: 3,
  timeoutMilliseconds: 10_000,
  retryDelayMilliseconds: Object.freeze([250, 500]),
});

const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);
const SUPPORTED_CYCLONEDX_VERSIONS = new Set(["1.5", "1.6", "1.7"]);
const VEX_TOP_LEVEL_KEYS = ["exceptions", "schemaVersion"];
const VEX_ENTRY_KEYS = [
  "advisory",
  "evidenceUrl",
  "expiresAt",
  "owner",
  "purl",
  "scope",
  "version",
];
const MAX_VEX_LIFETIME_MILLISECONDS = 90 * 24 * 60 * 60 * 1_000;
const SAFE_COORDINATE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$/u;
const SAFE_VERSION = /^[A-Za-z0-9][A-Za-z0-9_.+~()-]{0,254}$/u;
const SAFE_ADVISORY = /^[A-Za-z][A-Za-z0-9._-]{2,127}$/u;
const SAFE_OWNER = /^[\p{L}\p{N} .@_/-]{2,128}$/u;
const EXACT_UTC_SECONDS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u;
const OSV_UTC_TIMESTAMP =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/u;

const PUBLIC_ERROR_MESSAGES = Object.freeze({
  BOM_FILE_INVALID: "发布 CycloneDX BOM 不可读或超过安全上限。",
  BOM_JSON_INVALID: "发布 CycloneDX BOM 不是有效的 UTF-8 JSON。",
  BOM_SCHEMA_INVALID: "发布 CycloneDX BOM 不满足 runtime-closure 门禁契约。",
  BOM_COMPONENT_INVALID: "发布 CycloneDX BOM 含无效或不一致的受支持运行时组件。",
  CLI_INVALID_ARGUMENT: "OSV 门禁命令参数无效。",
  OSV_HTTP_LIMIT: "OSV 请求数量超过固定安全上限，门禁已关闭失败。",
  OSV_RESPONSE_INVALID: "OSV 返回内容无效或超过固定安全上限。",
  OSV_UNAVAILABLE: "OSV 服务不可用或超时，门禁已关闭失败。",
  VEX_FILE_INVALID: "VEX 文件不可读或超过安全上限。",
  VEX_JSON_INVALID: "VEX 文件不是有效的 UTF-8 JSON。",
  VEX_SCHEMA_INVALID: "VEX 必须使用精确、无扩展字段的受控契约。",
  VEX_EXPIRED: "VEX 已过期或有效期超过允许上限。",
  VEX_TARGET_NOT_IN_BOM: "VEX 的精确组件版本不在本次发布 runtime closure 中。",
});

export class SecurityGateError extends Error {
  constructor(code) {
    super(PUBLIC_ERROR_MESSAGES[code] ?? "依赖安全门禁发生未分类错误，已关闭失败。");
    this.name = "SecurityGateError";
    this.code = code;
  }
}

function gateError(code) {
  return new SecurityGateError(code);
}

export function enforceVerifiedTlsEnvironment(environment = process.env) {
  delete environment.NODE_TLS_REJECT_UNAUTHORIZED;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function compareCodeUnits(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function exactKeys(value, expected, errorCode) {
  if (!isPlainObject(value)) {
    throw gateError(errorCode);
  }
  const actual = Object.keys(value).sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    throw gateError(errorCode);
  }
}

function decodePurlPart(value, errorCode) {
  try {
    return decodeURIComponent(value);
  } catch {
    throw gateError(errorCode);
  }
}

function validateQualifiers(rawQualifiers, errorCode) {
  if (rawQualifiers === undefined) {
    return;
  }
  if (rawQualifiers.length === 0 || rawQualifiers.length > 1_024) {
    throw gateError(errorCode);
  }
  const seen = new Set();
  const qualifiers = rawQualifiers.split("&");
  if (qualifiers.length > 16) {
    throw gateError(errorCode);
  }
  for (const qualifier of qualifiers) {
    const match = qualifier.match(/^([A-Za-z][A-Za-z0-9._-]{0,63})=([A-Za-z0-9._~%:+-]{1,256})$/u);
    if (!match || seen.has(match[1])) {
      throw gateError(errorCode);
    }
    seen.add(match[1]);
  }
}

export function parseMavenPurl(rawPurl, { requireVersion = true, errorCode = "BOM_COMPONENT_INVALID" } = {}) {
  if (typeof rawPurl !== "string" || rawPurl.length === 0 || rawPurl.length > 2_048) {
    throw gateError(errorCode);
  }
  const match = rawPurl.match(
    /^pkg:maven\/([^/?#]+)\/([^@/?#]+)(?:@([^?#]+))?(?:\?([^#]*))?$/u,
  );
  if (!match) {
    throw gateError(errorCode);
  }
  const group = decodePurlPart(match[1], errorCode);
  const name = decodePurlPart(match[2], errorCode);
  const version = match[3] === undefined ? null : decodePurlPart(match[3], errorCode);
  if (!SAFE_COORDINATE.test(group) || !SAFE_COORDINATE.test(name)) {
    throw gateError(errorCode);
  }
  if (requireVersion && version === null) {
    throw gateError(errorCode);
  }
  if (version !== null && !SAFE_VERSION.test(version)) {
    throw gateError(errorCode);
  }
  validateQualifiers(match[4], errorCode);
  const packagePurl = `pkg:maven/${group}/${name}`;
  return {
    ecosystem: "Maven",
    group,
    name,
    osvName: `${group}:${name}`,
    packagePurl,
    version,
    versionedPurl: version === null ? packagePurl : `${packagePurl}@${version}`,
  };
}

export function parseNpmPurl(rawPurl, { requireVersion = true, errorCode = "BOM_COMPONENT_INVALID" } = {}) {
  if (typeof rawPurl !== "string" || rawPurl.length === 0 || rawPurl.length > 2_048) {
    throw gateError(errorCode);
  }
  const match = rawPurl.match(
    /^pkg:npm\/(?:(%40[^/@?#]+)\/)?([^/@?#]+)(?:@([^/?#]+))?$/u,
  );
  if (!match) {
    throw gateError(errorCode);
  }
  const rawNamespace = match[1] ?? null;
  const rawName = match[2];
  const rawVersion = match[3] ?? null;
  const namespace = rawNamespace === null ? null : decodePurlPart(rawNamespace, errorCode);
  const name = decodePurlPart(rawName, errorCode);
  const version = rawVersion === null ? null : decodePurlPart(rawVersion, errorCode);
  if (
    (namespace !== null && (!namespace.startsWith("@") || !SAFE_COORDINATE.test(namespace.slice(1)))) ||
    !SAFE_COORDINATE.test(name)
  ) {
    throw gateError(errorCode);
  }
  if (requireVersion && version === null) {
    throw gateError(errorCode);
  }
  if (version !== null && !SAFE_VERSION.test(version)) {
    throw gateError(errorCode);
  }
  // npm purls use a percent-encoded '@' for the optional scope. Requiring the
  // canonical spelling here prevents double-encoding and equivalent aliases
  // from becoming different OSV query or VEX identities.
  if (
    (namespace !== null && rawNamespace !== `%40${namespace.slice(1)}`) ||
    rawName !== name ||
    (version !== null && rawVersion !== version)
  ) {
    throw gateError(errorCode);
  }
  const encodedNamespace = namespace === null ? "" : `%40${namespace.slice(1)}/`;
  const packagePurl = `pkg:npm/${encodedNamespace}${name}`;
  const osvName = namespace === null ? name : `${namespace}/${name}`;
  return {
    ecosystem: "npm",
    namespace,
    name,
    osvName,
    packagePurl,
    version,
    versionedPurl: version === null ? packagePurl : `${packagePurl}@${version}`,
  };
}

export function extractRuntimeComponents(bom) {
  if (
    !isPlainObject(bom) ||
    bom.bomFormat !== "CycloneDX" ||
    !SUPPORTED_CYCLONEDX_VERSIONS.has(bom.specVersion) ||
    !Number.isInteger(bom.version) ||
    bom.version < 1 ||
    !Array.isArray(bom.components) ||
    bom.components.length === 0 ||
    bom.components.length > LIMITS.components
  ) {
    throw gateError("BOM_SCHEMA_INVALID");
  }

  const componentsByVersionedPurl = new Map();
  let ignoredOtherComponents = 0;
  for (const component of bom.components) {
    if (!isPlainObject(component)) {
      throw gateError("BOM_COMPONENT_INVALID");
    }
    if (component.purl === undefined) {
      ignoredOtherComponents += 1;
      continue;
    }
    if (typeof component.purl !== "string") {
      throw gateError("BOM_COMPONENT_INVALID");
    }
    let parsed;
    if (component.purl.startsWith("pkg:maven/")) {
      parsed = parseMavenPurl(component.purl);
    } else if (component.purl.startsWith("pkg:npm/")) {
      parsed = parseNpmPurl(component.purl);
    } else {
      ignoredOtherComponents += 1;
      continue;
    }
    if (typeof component.version !== "string" || component.version !== parsed.version) {
      throw gateError("BOM_COMPONENT_INVALID");
    }
    if (
      parsed.ecosystem === "npm" &&
      (typeof component.name !== "string" || component.name !== parsed.osvName)
    ) {
      throw gateError("BOM_COMPONENT_INVALID");
    }
    if (
      component.scope !== undefined &&
      !["required", "optional", "excluded"].includes(component.scope)
    ) {
      throw gateError("BOM_COMPONENT_INVALID");
    }
    componentsByVersionedPurl.set(parsed.versionedPurl, parsed);
  }

  const components = [...componentsByVersionedPurl.values()].sort((left, right) =>
    compareCodeUnits(left.versionedPurl, right.versionedPurl),
  );
  if (components.length === 0) {
    throw gateError("BOM_SCHEMA_INVALID");
  }
  return {
    components,
    ignoredOtherComponents,
    mavenComponents: components.filter((component) => component.ecosystem === "Maven").length,
    npmComponents: components.filter((component) => component.ecosystem === "npm").length,
  };
}

// Retained for callers that still need the old Maven-only projection. The
// release gate itself uses extractRuntimeComponents so Console dependencies
// can no longer disappear from vulnerability evidence.
export function extractRuntimeMavenComponents(bom) {
  const extracted = extractRuntimeComponents(bom);
  const components = extracted.components.filter((component) => component.ecosystem === "Maven");
  if (components.length === 0) {
    throw gateError("BOM_SCHEMA_INVALID");
  }
  return {
    components,
    ignoredNonMavenComponents:
      extracted.ignoredOtherComponents + extracted.npmComponents,
  };
}

function parseExactExpiry(value, now) {
  if (typeof value !== "string" || !EXACT_UTC_SECONDS.test(value)) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds)) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  const normalized = new Date(milliseconds).toISOString().replace(".000Z", "Z");
  if (normalized !== value) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  const remaining = milliseconds - now.getTime();
  if (remaining <= 0 || remaining > MAX_VEX_LIFETIME_MILLISECONDS) {
    throw gateError("VEX_EXPIRED");
  }
  return milliseconds;
}

function parseOsvUtcTimestamp(value) {
  if (typeof value !== "string" || value.length > 64) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  const match = value.match(OSV_UTC_TIMESTAMP);
  if (!match) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  const milliseconds = Date.UTC(year, month - 1, day, hour, minute, second);
  const date = new Date(milliseconds);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day ||
    date.getUTCHours() !== hour ||
    date.getUTCMinutes() !== minute ||
    date.getUTCSeconds() !== second
  ) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  return milliseconds;
}

function validateEvidenceUrl(value) {
  if (typeof value !== "string" || value.length > 512) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  let url;
  try {
    url = new URL(value);
  } catch {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  if (
    url.protocol !== "https:" ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== "" ||
    url.hostname.length === 0
  ) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
}

export function validateVex(vex, { now = new Date() } = {}) {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw gateError("VEX_SCHEMA_INVALID");
  }
  exactKeys(vex, VEX_TOP_LEVEL_KEYS, "VEX_SCHEMA_INVALID");
  if (
    vex.schemaVersion !== 1 ||
    !Array.isArray(vex.exceptions) ||
    vex.exceptions.length > LIMITS.vexEntries
  ) {
    throw gateError("VEX_SCHEMA_INVALID");
  }

  const seen = new Set();
  const entries = vex.exceptions.map((entry) => {
    exactKeys(entry, VEX_ENTRY_KEYS, "VEX_SCHEMA_INVALID");
    if (
      typeof entry.advisory !== "string" ||
      !SAFE_ADVISORY.test(entry.advisory) ||
      entry.scope !== RUNTIME_SCOPE ||
      typeof entry.owner !== "string" ||
      !SAFE_OWNER.test(entry.owner) ||
      entry.owner !== entry.owner.trim() ||
      !/[\p{L}\p{N}@_]/u.test(entry.owner) ||
      typeof entry.version !== "string" ||
      !SAFE_VERSION.test(entry.version)
    ) {
      throw gateError("VEX_SCHEMA_INVALID");
    }
    const parsed = parseMavenPurl(entry.purl, {
      requireVersion: false,
      errorCode: "VEX_SCHEMA_INVALID",
    });
    if (parsed.version !== null || entry.purl !== parsed.packagePurl) {
      throw gateError("VEX_SCHEMA_INVALID");
    }
    validateEvidenceUrl(entry.evidenceUrl);
    parseExactExpiry(entry.expiresAt, now);
    const key = `${entry.advisory}\u0000${entry.purl}\u0000${entry.version}\u0000${entry.scope}`;
    if (seen.has(key)) {
      throw gateError("VEX_SCHEMA_INVALID");
    }
    seen.add(key);
    return Object.freeze({ ...entry, key, versionedPurl: `${entry.purl}@${entry.version}` });
  });
  return entries.sort((left, right) => compareCodeUnits(left.key, right.key));
}

function bindVexToRuntimeClosure(entries, components) {
  const componentPurls = new Set(components.map((component) => component.versionedPurl));
  for (const entry of entries) {
    if (!componentPurls.has(entry.versionedPurl)) {
      throw gateError("VEX_TARGET_NOT_IN_BOM");
    }
  }
}

function parseVectorMetrics(vector, prefix, errorCode) {
  if (typeof vector !== "string" || vector.length === 0 || vector.length > 512) {
    throw gateError(errorCode);
  }
  const body = prefix === null ? vector : vector.startsWith(prefix) ? vector.slice(prefix.length) : null;
  if (body === null || body.length === 0) {
    throw gateError(errorCode);
  }
  const metrics = new Map();
  for (const token of body.split("/")) {
    const match = token.match(/^([A-Za-z][A-Za-z0-9]{0,7}):([A-Za-z0-9_-]{1,16})$/u);
    if (!match || metrics.has(match[1])) {
      throw gateError(errorCode);
    }
    metrics.set(match[1], match[2]);
  }
  return metrics;
}

function metric(metrics, name, values) {
  const selected = metrics.get(name);
  if (selected === undefined || values[selected] === undefined) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  return values[selected];
}

function roundUpToOneDecimal(value) {
  return Math.ceil(value * 10 - 1e-7) / 10;
}

function scoreCvssV3(vector) {
  const prefix = vector.startsWith("CVSS:3.1/") ? "CVSS:3.1/" : "CVSS:3.0/";
  const metrics = parseVectorMetrics(vector, prefix, "OSV_RESPONSE_INVALID");
  const scope = metric(metrics, "S", { U: "U", C: "C" });
  const attackVector = metric(metrics, "AV", { N: 0.85, A: 0.62, L: 0.55, P: 0.2 });
  const attackComplexity = metric(metrics, "AC", { L: 0.77, H: 0.44 });
  const privilegesRequired = metric(
    metrics,
    "PR",
    scope === "C" ? { N: 0.85, L: 0.68, H: 0.5 } : { N: 0.85, L: 0.62, H: 0.27 },
  );
  const userInteraction = metric(metrics, "UI", { N: 0.85, R: 0.62 });
  const confidentiality = metric(metrics, "C", { H: 0.56, L: 0.22, N: 0 });
  const integrity = metric(metrics, "I", { H: 0.56, L: 0.22, N: 0 });
  const availability = metric(metrics, "A", { H: 0.56, L: 0.22, N: 0 });
  const impactSubScore = 1 - (1 - confidentiality) * (1 - integrity) * (1 - availability);
  const impact =
    scope === "U"
      ? 6.42 * impactSubScore
      : 7.52 * (impactSubScore - 0.029) - 3.25 * (impactSubScore - 0.02) ** 15;
  if (impact <= 0) {
    return 0;
  }
  const exploitability =
    8.22 * attackVector * attackComplexity * privilegesRequired * userInteraction;
  const raw = scope === "U" ? impact + exploitability : 1.08 * (impact + exploitability);
  return roundUpToOneDecimal(Math.min(raw, 10));
}

function scoreCvssV2(vector) {
  const body = vector.startsWith("CVSS:2.0/") ? vector.slice("CVSS:2.0/".length) : vector;
  const metrics = parseVectorMetrics(body, null, "OSV_RESPONSE_INVALID");
  const attackVector = metric(metrics, "AV", { L: 0.395, A: 0.646, N: 1.0 });
  const attackComplexity = metric(metrics, "AC", { H: 0.35, M: 0.61, L: 0.71 });
  const authentication = metric(metrics, "Au", { M: 0.45, S: 0.56, N: 0.704 });
  const confidentiality = metric(metrics, "C", { N: 0, P: 0.275, C: 0.66 });
  const integrity = metric(metrics, "I", { N: 0, P: 0.275, C: 0.66 });
  const availability = metric(metrics, "A", { N: 0, P: 0.275, C: 0.66 });
  const impact = 10.41 * (1 - (1 - confidentiality) * (1 - integrity) * (1 - availability));
  const exploitability = 20 * attackVector * attackComplexity * authentication;
  const impactFactor = impact === 0 ? 0 : 1.176;
  return Math.round(((0.6 * impact + 0.4 * exploitability - 1.5) * impactFactor) * 10) / 10;
}

function validateCvssV4Vector(vector) {
  const metrics = parseVectorMetrics(vector, "CVSS:4.0/", "OSV_RESPONSE_INVALID");
  metric(metrics, "AV", { N: true, A: true, L: true, P: true });
  metric(metrics, "AC", { L: true, H: true });
  metric(metrics, "AT", { N: true, P: true });
  metric(metrics, "PR", { N: true, L: true, H: true });
  metric(metrics, "UI", { N: true, P: true, A: true });
  for (const name of ["VC", "VI", "VA", "SC", "SI", "SA"]) {
    metric(metrics, name, { H: true, L: true, N: true });
  }
}

export function scoreCvssSeverity(severityEntries) {
  if (!Array.isArray(severityEntries)) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  const scores = [];
  let hasCvssV4 = false;
  for (const entry of severityEntries) {
    if (!isPlainObject(entry) || typeof entry.type !== "string" || typeof entry.score !== "string") {
      throw gateError("OSV_RESPONSE_INVALID");
    }
    if (entry.type === "CVSS_V3") {
      scores.push({ score: scoreCvssV3(entry.score), cvssVersion: entry.score.slice(5, 8) });
    } else if (entry.type === "CVSS_V2") {
      scores.push({ score: scoreCvssV2(entry.score), cvssVersion: "2.0" });
    } else if (entry.type === "CVSS_V4") {
      validateCvssV4Vector(entry.score);
      hasCvssV4 = true;
    } else {
      throw gateError("OSV_RESPONSE_INVALID");
    }
  }
  scores.sort((left, right) => right.score - left.score || compareCodeUnits(right.cvssVersion, left.cvssVersion));
  // Until a FIRST-vector-tested CVSS v4 calculator is available, a v4
  // vector must remain an unknown blocking signal even when an older v2/v3
  // vector is also present. Otherwise a low legacy score could silently
  // downgrade a high v4 assessment.
  if (hasCvssV4) {
    return { score: null, cvssVersion: "4.0-unsupported" };
  }
  if (scores.length > 0) {
    return scores[0];
  }
  return { score: null, cvssVersion: null };
}

function matchesAffectedPackage(affected, component) {
  if (!isPlainObject(affected) || !isPlainObject(affected.package)) {
    return false;
  }
  const package_ = affected.package;
  if (
    typeof package_.purl === "string" &&
    (package_.purl.startsWith("pkg:maven/") || package_.purl.startsWith("pkg:npm/"))
  ) {
    try {
      const parser = package_.purl.startsWith("pkg:maven/") ? parseMavenPurl : parseNpmPurl;
      return parser(package_.purl, {
        requireVersion: false,
        errorCode: "OSV_RESPONSE_INVALID",
      }).packagePurl === component.packagePurl;
    } catch (error) {
      if (error instanceof SecurityGateError) {
        throw error;
      }
      throw gateError("OSV_RESPONSE_INVALID");
    }
  }
  return (
    package_.ecosystem === component.ecosystem &&
    package_.name === component.osvName
  );
}

function severityForComponent(vulnerability, component) {
  if (vulnerability.affected !== undefined && !Array.isArray(vulnerability.affected)) {
    throw gateError("OSV_RESPONSE_INVALID");
  }
  const packageSeverity = [];
  for (const affected of vulnerability.affected ?? []) {
    if (matchesAffectedPackage(affected, component) && affected.severity !== undefined) {
      if (!Array.isArray(affected.severity)) {
        throw gateError("OSV_RESPONSE_INVALID");
      }
      packageSeverity.push(...affected.severity);
    }
  }
  if (packageSeverity.length > 0) {
    return scoreCvssSeverity(packageSeverity);
  }
  if (vulnerability.severity === undefined) {
    return { score: null, cvssVersion: null };
  }
  return scoreCvssSeverity(vulnerability.severity);
}

function qualitativeSeverity(score, cvssVersion) {
  if (score === null) {
    return "UNKNOWN";
  }
  if (score >= 9.0 && cvssVersion !== "2.0") {
    return "CRITICAL";
  }
  if (score >= 7.0) {
    return "HIGH";
  }
  if (score >= 4.0) {
    return "MEDIUM";
  }
  if (score > 0) {
    return "LOW";
  }
  return "NONE";
}

async function readResponseJson(response) {
  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null) {
    const parsedLength = Number(declaredLength);
    if (!Number.isSafeInteger(parsedLength) || parsedLength < 0 || parsedLength > LIMITS.responseBytes) {
      throw gateError("OSV_RESPONSE_INVALID");
    }
  }

  const chunks = [];
  let bytes = 0;
  if (response.body && typeof response.body.getReader === "function") {
    const reader = response.body.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      bytes += value.byteLength;
      if (bytes > LIMITS.responseBytes) {
        await reader.cancel().catch(() => {});
        throw gateError("OSV_RESPONSE_INVALID");
      }
      chunks.push(value);
    }
  } else {
    const value = new Uint8Array(await response.arrayBuffer());
    bytes = value.byteLength;
    if (bytes > LIMITS.responseBytes) {
      throw gateError("OSV_RESPONSE_INVALID");
    }
    chunks.push(value);
  }

  const combined = new Uint8Array(bytes);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(combined);
    return JSON.parse(text);
  } catch {
    throw gateError("OSV_RESPONSE_INVALID");
  }
}

function defaultSleep(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

class OsvClient {
  constructor({ fetchImpl, sleepImpl, httpPolicy }) {
    if (typeof fetchImpl !== "function") {
      throw gateError("OSV_UNAVAILABLE");
    }
    this.fetchImpl = fetchImpl;
    this.sleepImpl = sleepImpl;
    this.httpPolicy = httpPolicy;
    this.requestCount = 0;
  }

  async requestJson(path, init) {
    for (let attempt = 0; attempt < this.httpPolicy.attempts; attempt += 1) {
      if (this.requestCount >= LIMITS.httpRequests) {
        throw gateError("OSV_HTTP_LIMIT");
      }
      this.requestCount += 1;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), this.httpPolicy.timeoutMilliseconds);
      let response;
      try {
        response = await this.fetchImpl(`${OSV_BASE_URL}${path}`, {
          ...init,
          redirect: "error",
          signal: controller.signal,
          headers: {
            accept: "application/json",
            ...(init.body === undefined ? {} : { "content-type": "application/json" }),
          },
        });
      } catch {
        clearTimeout(timeout);
        if (attempt + 1 < this.httpPolicy.attempts) {
          await this.sleepImpl(this.httpPolicy.retryDelayMilliseconds[attempt]);
          continue;
        }
        throw gateError("OSV_UNAVAILABLE");
      }
      if (!response || typeof response.ok !== "boolean" || typeof response.status !== "number") {
        clearTimeout(timeout);
        throw gateError("OSV_RESPONSE_INVALID");
      }
      if (!response.ok) {
        clearTimeout(timeout);
        if (response.body && typeof response.body.cancel === "function") {
          await response.body.cancel().catch(() => {});
        }
        if (RETRYABLE_STATUS.has(response.status) && attempt + 1 < this.httpPolicy.attempts) {
          await this.sleepImpl(this.httpPolicy.retryDelayMilliseconds[attempt]);
          continue;
        }
        throw gateError(RETRYABLE_STATUS.has(response.status) ? "OSV_UNAVAILABLE" : "OSV_RESPONSE_INVALID");
      }
      try {
        const value = await readResponseJson(response);
        clearTimeout(timeout);
        return value;
      } catch (error) {
        clearTimeout(timeout);
        if (error instanceof SecurityGateError) {
          throw error;
        }
        if (attempt + 1 < this.httpPolicy.attempts) {
          await this.sleepImpl(this.httpPolicy.retryDelayMilliseconds[attempt]);
          continue;
        }
        throw gateError("OSV_UNAVAILABLE");
      }
    }
    throw gateError("OSV_UNAVAILABLE");
  }

  async queryComponents(components) {
    const idsByPurl = new Map(components.map((component) => [component.versionedPurl, new Set()]));
    for (let offset = 0; offset < components.length; offset += LIMITS.batchSize) {
      let pending = components.slice(offset, offset + LIMITS.batchSize).map((component) => ({
        component,
        page: 0,
        pageToken: null,
      }));
      while (pending.length > 0) {
        const queries = pending.map(({ component, pageToken }) => ({
          package: { purl: component.versionedPurl },
          ...(pageToken === null ? {} : { page_token: pageToken }),
        }));
        const response = await this.requestJson("/v1/querybatch", {
          method: "POST",
          body: JSON.stringify({ queries }),
        });
        if (!isPlainObject(response) || !Array.isArray(response.results) || response.results.length !== pending.length) {
          throw gateError("OSV_RESPONSE_INVALID");
        }
        const next = [];
        for (let index = 0; index < pending.length; index += 1) {
          const result = response.results[index];
          if (!isPlainObject(result)) {
            throw gateError("OSV_RESPONSE_INVALID");
          }
          const vulns = result.vulns ?? [];
          if (!Array.isArray(vulns)) {
            throw gateError("OSV_RESPONSE_INVALID");
          }
          for (const vulnerability of vulns) {
            if (
              !isPlainObject(vulnerability) ||
              typeof vulnerability.id !== "string" ||
              !SAFE_ADVISORY.test(vulnerability.id) ||
              (vulnerability.modified !== undefined && typeof vulnerability.modified !== "string")
            ) {
              throw gateError("OSV_RESPONSE_INVALID");
            }
            idsByPurl.get(pending[index].component.versionedPurl).add(vulnerability.id);
          }
          if (result.next_page_token !== undefined) {
            if (
              typeof result.next_page_token !== "string" ||
              result.next_page_token.length === 0 ||
              result.next_page_token.length > 4_096 ||
              pending[index].page + 1 >= LIMITS.batchPages
            ) {
              throw gateError("OSV_RESPONSE_INVALID");
            }
            next.push({
              component: pending[index].component,
              page: pending[index].page + 1,
              pageToken: result.next_page_token,
            });
          }
        }
        pending = next;
      }
    }
    const uniqueIds = new Set();
    for (const ids of idsByPurl.values()) {
      for (const id of ids) {
        uniqueIds.add(id);
      }
    }
    if (uniqueIds.size > LIMITS.vulnerabilityIds) {
      throw gateError("OSV_HTTP_LIMIT");
    }
    return idsByPurl;
  }

  async getVulnerabilities(ids) {
    const details = new Map();
    const sortedIds = [...ids].sort(compareCodeUnits);
    for (let offset = 0; offset < sortedIds.length; offset += 8) {
      const page = sortedIds.slice(offset, offset + 8);
      const values = await Promise.all(
        page.map((id) => this.requestJson(`/v1/vulns/${encodeURIComponent(id)}`, { method: "GET" })),
      );
      for (let index = 0; index < page.length; index += 1) {
        const id = page[index];
        const vulnerability = values[index];
        if (!isPlainObject(vulnerability) || vulnerability.id !== id) {
          throw gateError("OSV_RESPONSE_INVALID");
        }
        if (
          typeof vulnerability.modified !== "string" ||
          (vulnerability.withdrawn !== undefined && typeof vulnerability.withdrawn !== "string")
        ) {
          throw gateError("OSV_RESPONSE_INVALID");
        }
        parseOsvUtcTimestamp(vulnerability.modified);
        if (vulnerability.withdrawn !== undefined) {
          parseOsvUtcTimestamp(vulnerability.withdrawn);
        }
        if (vulnerability.aliases !== undefined) {
          if (
            !Array.isArray(vulnerability.aliases) ||
            vulnerability.aliases.length > 128 ||
            vulnerability.aliases.some((alias) => typeof alias !== "string" || !SAFE_ADVISORY.test(alias))
          ) {
            throw gateError("OSV_RESPONSE_INVALID");
          }
        }
        details.set(id, vulnerability);
      }
    }
    return details;
  }
}

function findVexEntry(entries, vulnerability, component) {
  const advisoryIds = new Set([vulnerability.id, ...(vulnerability.aliases ?? [])]);
  return entries.find(
    (entry) => entry.versionedPurl === component.versionedPurl && advisoryIds.has(entry.advisory),
  );
}

function createSummary({
  status,
  mavenComponents,
  npmComponents,
  ignoredOtherComponents,
  osvMatches,
  belowThresholdMatches,
  exemptedMatches,
  withdrawnMatches,
  blockingFindings,
  vexEntries,
  matchedVexKeys,
  httpRequests,
}) {
  const sortedFindings = [...blockingFindings].sort((left, right) =>
    compareCodeUnits(
      `${left.purl}@${left.version}\u0000${left.advisory}`,
      `${right.purl}@${right.version}\u0000${right.advisory}`,
    ),
  );
  return {
    schemaVersion: 1,
    gate: GATE_NAME,
    status,
    policy: {
      source: "CycloneDX release runtime closure",
      ecosystems: ["Maven", "npm"],
      blockingCvssScore: BLOCKING_CVSS_SCORE,
      unknownCvss: "block",
      vexScope: RUNTIME_SCOPE,
    },
    counts: {
      mavenComponents,
      npmComponents,
      ignoredOtherComponents,
      osvMatches,
      belowThresholdMatches,
      exemptedMatches,
      withdrawnMatches,
      blockingMatches: sortedFindings.length,
      httpRequests,
    },
    vex: {
      entries: vexEntries.length,
      matchedEntries: matchedVexKeys.size,
      unusedEntries: vexEntries.length - matchedVexKeys.size,
      nearestExpiry: vexEntries.length === 0
        ? null
        : vexEntries.map((entry) => entry.expiresAt).sort()[0],
    },
    blockingFindings: sortedFindings,
    errors: [],
  };
}

export function createErrorSummary(error) {
  const code = error instanceof SecurityGateError ? error.code : "UNCLASSIFIED_FAILURE";
  const message = PUBLIC_ERROR_MESSAGES[code] ?? "依赖安全门禁发生未分类错误，已关闭失败。";
  return {
    schemaVersion: 1,
    gate: GATE_NAME,
    status: "error",
    policy: {
      source: "CycloneDX release runtime closure",
      ecosystems: ["Maven", "npm"],
      blockingCvssScore: BLOCKING_CVSS_SCORE,
      unknownCvss: "block",
      vexScope: RUNTIME_SCOPE,
    },
    counts: {
      mavenComponents: 0,
      npmComponents: 0,
      ignoredOtherComponents: 0,
      osvMatches: 0,
      belowThresholdMatches: 0,
      exemptedMatches: 0,
      withdrawnMatches: 0,
      blockingMatches: 0,
      httpRequests: 0,
    },
    vex: {
      entries: 0,
      matchedEntries: 0,
      unusedEntries: 0,
      nearestExpiry: null,
    },
    blockingFindings: [],
    errors: [{ code, message }],
  };
}

export async function runGate({
  bom,
  vex,
  fetchImpl = globalThis.fetch,
  now = new Date(),
  sleepImpl = defaultSleep,
  testHttpPolicy,
} = {}) {
  const {
    components,
    ignoredOtherComponents,
    mavenComponents,
    npmComponents,
  } = extractRuntimeComponents(bom);
  const vexEntries = validateVex(vex, { now });
  bindVexToRuntimeClosure(vexEntries, components);
  const httpPolicy = testHttpPolicy === undefined
    ? HTTP_POLICY
    : Object.freeze({
      attempts: testHttpPolicy.attempts,
      timeoutMilliseconds: testHttpPolicy.timeoutMilliseconds,
      retryDelayMilliseconds: Object.freeze([...testHttpPolicy.retryDelayMilliseconds]),
    });
  if (
    !Number.isInteger(httpPolicy.attempts) ||
    httpPolicy.attempts < 1 ||
    httpPolicy.attempts > HTTP_POLICY.attempts ||
    !Number.isInteger(httpPolicy.timeoutMilliseconds) ||
    httpPolicy.timeoutMilliseconds < 1 ||
    httpPolicy.timeoutMilliseconds > HTTP_POLICY.timeoutMilliseconds ||
    httpPolicy.retryDelayMilliseconds.length !== Math.max(0, httpPolicy.attempts - 1)
    || httpPolicy.retryDelayMilliseconds.some(
      (delay) => !Number.isInteger(delay) || delay < 0 || delay > 5_000,
    )
  ) {
    throw gateError("CLI_INVALID_ARGUMENT");
  }
  const client = new OsvClient({ fetchImpl, sleepImpl, httpPolicy });
  const idsByPurl = await client.queryComponents(components);
  const uniqueIds = new Set();
  for (const ids of idsByPurl.values()) {
    for (const id of ids) {
      uniqueIds.add(id);
    }
  }
  const vulnerabilities = await client.getVulnerabilities(uniqueIds);

  let osvMatches = 0;
  let belowThresholdMatches = 0;
  let exemptedMatches = 0;
  let withdrawnMatches = 0;
  const blockingFindings = [];
  const matchedVexKeys = new Set();
  for (const component of components) {
    const ids = [...idsByPurl.get(component.versionedPurl)].sort(compareCodeUnits);
    for (const id of ids) {
      osvMatches += 1;
      const vulnerability = vulnerabilities.get(id);
      if (
        typeof vulnerability.withdrawn === "string" &&
        parseOsvUtcTimestamp(vulnerability.withdrawn) <= now.getTime()
      ) {
        withdrawnMatches += 1;
        continue;
      }
      const vexEntry = findVexEntry(vexEntries, vulnerability, component);
      if (vexEntry !== undefined) {
        exemptedMatches += 1;
        matchedVexKeys.add(vexEntry.key);
        continue;
      }
      const { score, cvssVersion } = severityForComponent(vulnerability, component);
      if (score !== null && score < BLOCKING_CVSS_SCORE) {
        belowThresholdMatches += 1;
        continue;
      }
      blockingFindings.push({
        advisory: vulnerability.id,
        purl: component.packagePurl,
        version: component.version,
        severity: qualitativeSeverity(score, cvssVersion),
        cvssScore: score,
        cvssVersion,
        reason: score === null ? "NO_SUPPORTED_CVSS_SCORE" : "CVSS_HIGH_OR_CRITICAL",
      });
    }
  }

  return createSummary({
    status: blockingFindings.length === 0 ? "pass" : "blocked",
    mavenComponents,
    npmComponents,
    ignoredOtherComponents,
    osvMatches,
    belowThresholdMatches,
    exemptedMatches,
    withdrawnMatches,
    blockingFindings,
    vexEntries,
    matchedVexKeys,
    httpRequests: client.requestCount,
  });
}

async function readBoundedJson(path, maximumBytes, fileErrorCode, jsonErrorCode) {
  let metadata;
  let bytes;
  try {
    metadata = await lstat(path);
    if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size <= 0 || metadata.size > maximumBytes) {
      throw gateError(fileErrorCode);
    }
    bytes = await readFile(path);
  } catch (error) {
    if (error instanceof SecurityGateError) {
      throw error;
    }
    throw gateError(fileErrorCode);
  }
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return JSON.parse(text);
  } catch {
    throw gateError(jsonErrorCode);
  }
}

function parseCommandLine(arguments_) {
  const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
  const options = {
    bomPath: resolve(repositoryRoot, "build/reports/cyclonedx-release/bom.json"),
    vexPath: resolve(repositoryRoot, "gradle/osv-vex.json"),
  };
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    const value = arguments_[index + 1];
    if (argument === "--bom" && value !== undefined) {
      options.bomPath = resolve(value);
      index += 1;
    } else if (argument === "--vex" && value !== undefined) {
      options.vexPath = resolve(value);
      index += 1;
    } else {
      throw gateError("CLI_INVALID_ARGUMENT");
    }
  }
  return options;
}

async function main() {
  // A caller's inherited shell must never be able to turn this security gate
  // into an unauthenticated HTTPS query. Deleting the Node escape hatch
  // restores normal CA verification; private CA trust must use the standard
  // reviewed certificate mechanisms instead.
  enforceVerifiedTlsEnvironment();
  try {
    const { bomPath, vexPath } = parseCommandLine(process.argv.slice(2));
    const [bom, vex] = await Promise.all([
      readBoundedJson(bomPath, LIMITS.bomBytes, "BOM_FILE_INVALID", "BOM_JSON_INVALID"),
      readBoundedJson(vexPath, LIMITS.vexBytes, "VEX_FILE_INVALID", "VEX_JSON_INVALID"),
    ]);
    const summary = await runGate({ bom, vex });
    process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
    process.exitCode = summary.status === "pass" ? 0 : 1;
  } catch (error) {
    process.stdout.write(`${JSON.stringify(createErrorSummary(error), null, 2)}\n`);
    process.exitCode = 1;
  }
}

const invokedPath = process.argv[1] === undefined ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  await main();
}
