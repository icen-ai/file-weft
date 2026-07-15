import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const projectDirectory = join(dirname(fileURLToPath(import.meta.url)), "..");
const packageDocument = JSON.parse(readFileSync(join(projectDirectory, "package.json"), "utf8"));
const npmExecutable = process.env.npm_execpath;
const command = npmExecutable ? process.execPath : process.platform === "win32" ? "npm.cmd" : "npm";
const args = npmExecutable ? [npmExecutable] : [];
args.push(
  "sbom",
  "--package-lock-only",
  "--omit=dev",
  "--sbom-format=cyclonedx",
  "--sbom-type=application",
);
const environment = { ...process.env };
delete environment.NODE_TLS_REJECT_UNAUTHORIZED;
const generated = spawnSync(command, args, {
  cwd: projectDirectory,
  encoding: "utf8",
  env: environment,
  maxBuffer: 16 * 1_024 * 1_024,
  shell: false,
});
if (generated.status !== 0 || !generated.stdout) {
  throw new Error("npm failed to generate the Console production SBOM.");
}

let bom;
try {
  bom = JSON.parse(generated.stdout);
} catch {
  throw new Error("npm returned an invalid Console SBOM.");
}
validateBom(bom, packageDocument);
delete bom.serialNumber;
delete bom.metadata.timestamp;
bom.components.sort((left, right) => left["bom-ref"].localeCompare(right["bom-ref"]));
bom.dependencies.forEach((dependency) => dependency.dependsOn.sort());
bom.dependencies.sort((left, right) => left.ref.localeCompare(right.ref));

const outputDirectory = join(projectDirectory, "build", "reports", "cyclonedx");
mkdirSync(outputDirectory, { recursive: true });
const canonical = `${JSON.stringify(bom, null, 2)}\n`;
writeFileSync(join(outputDirectory, "bom.json"), canonical, "utf8");
writeFileSync(
  join(outputDirectory, "bom.json.sha256"),
  `${createHash("sha256").update(canonical, "utf8").digest("hex")}  bom.json\n`,
  "utf8",
);
process.stdout.write(`Console CycloneDX SBOM: ${bom.components.length} runtime components.\n`);

function validateBom(document, packageJson) {
  if (!document || document.bomFormat !== "CycloneDX" || document.specVersion !== "1.5" ||
    !document.metadata || !document.metadata.component) {
    throw new Error("Console SBOM does not use the reviewed CycloneDX 1.5 shape.");
  }
  const root = document.metadata.component;
  const encodedName = encodeURIComponent(packageJson.name).replace("%2F", "/");
  const expectedRootPurl = `pkg:npm/${encodedName}@${packageJson.version}`;
  if (root.type !== "application" || root.name !== "flowweft-console" ||
    root.version !== packageJson.version || root.purl !== expectedRootPurl ||
    root["bom-ref"] !== `${packageJson.name}@${packageJson.version}`) {
    throw new Error("Console SBOM root identity differs from package.json.");
  }
  if (!Array.isArray(document.components) || document.components.length < 1 ||
    document.components.length > 5_000 || !Array.isArray(document.dependencies) ||
    document.dependencies.length < 1 || document.dependencies.length > 5_001) {
    throw new Error("Console SBOM component or dependency inventory is empty or unbounded.");
  }
  const references = new Set([root["bom-ref"]]);
  for (const component of document.components) {
    if (!component || component.type !== "library" || typeof component["bom-ref"] !== "string" ||
      typeof component.name !== "string" || typeof component.version !== "string" ||
      typeof component.purl !== "string" || !component.purl.startsWith("pkg:npm/") ||
      references.has(component["bom-ref"]) || !Array.isArray(component.hashes) ||
      !component.hashes.some((hash) => hash?.alg === "SHA-512" && /^[0-9a-f]{128}$/u.test(hash.content)) ||
      !Array.isArray(component.licenses) || component.licenses.length < 1) {
      throw new Error("Console SBOM contains an invalid, duplicate, unhashed, or unlicensed component.");
    }
    references.add(component["bom-ref"]);
  }
  const dependencyReferences = new Set();
  for (const dependency of document.dependencies) {
    if (!dependency || typeof dependency.ref !== "string" || !references.has(dependency.ref) ||
      dependencyReferences.has(dependency.ref) || !Array.isArray(dependency.dependsOn) ||
      dependency.dependsOn.some((reference) => !references.has(reference))) {
      throw new Error("Console SBOM dependency graph is invalid or contains dangling references.");
    }
    dependencyReferences.add(dependency.ref);
  }
  if (dependencyReferences.size !== references.size) {
    throw new Error("Console SBOM dependency graph does not cover every component.");
  }
}
