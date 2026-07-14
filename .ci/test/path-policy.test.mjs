import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const sharedConfiguration = readFileSync(new URL("../.shared.yml", import.meta.url), "utf8");
const knowledgeConfiguration = readFileSync(new URL("../knowledge.yml", import.meta.url), "utf8");
const repositoryConfiguration = readFileSync(new URL("../../.cnb.yml", import.meta.url), "utf8");
const repositorySettings = readFileSync(new URL("../../.cnb/settings.yml", import.meta.url), "utf8");
const lines = `${sharedConfiguration}\n${knowledgeConfiguration}`.split(/\r?\n/u);
const prConfiguration = readFileSync(new URL("../pr.yml", import.meta.url), "utf8");
const mainConfiguration = readFileSync(new URL("../main.yml", import.meta.url), "utf8");
const releaseConfiguration = readFileSync(new URL("../release.yml", import.meta.url), "utf8");
const composeConfiguration = readFileSync(
  new URL("../../.docker/docker-compose.dev.yaml", import.meta.url),
  "utf8",
);
const kingbaseBash = readFileSync(
  new URL("../scripts/prepare-kingbase-image.sh", import.meta.url),
  "utf8",
);
const kingbasePowerShell = readFileSync(
  new URL("../scripts/prepare-kingbase-image.ps1", import.meta.url),
  "utf8",
);

const groupNames = [
  ".fileweft-docs-paths",
  ".fileweft-knowledge-paths",
  ".fileweft-fast-paths",
  ".fileweft-jvm8-paths",
  ".fileweft-jvm17-paths",
  ".fileweft-jvm-all-paths",
  ".fileweft-postgres-paths",
  ".fileweft-mysql-paths",
  ".fileweft-kingbase-paths",
  ".fileweft-rustfs-paths",
  ".fileweft-e2e-paths",
  ".fileweft-release-artifact-paths",
];

function unquote(value) {
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    return value.slice(1, -1);
  }
  return value;
}

function readPathGroup(name) {
  const start = lines.findIndex((line) => line === `${name}:`);
  assert.notEqual(start, -1, `missing CNB path group ${name}`);

  const values = [];
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.length > 0 && !line.startsWith(" ")) {
      break;
    }
    const item = line.match(/^  - (.+)$/u);
    if (item) {
      values.push(unquote(item[1]));
    }
  }
  assert.ok(values.length > 0, `CNB path group ${name} must not be empty`);
  return values;
}

function globRegex(glob) {
  let expression = "^";
  for (let index = 0; index < glob.length; index += 1) {
    const character = glob[index];
    const next = glob[index + 1];
    const afterNext = glob[index + 2];
    if (character === "*" && next === "*" && afterNext === "/") {
      expression += "(?:.*/)?";
      index += 2;
    } else if (character === "*" && next === "*") {
      expression += ".*";
      index += 1;
    } else if (character === "*") {
      expression += "[^/]*";
    } else if (character === "?") {
      expression += "[^/]";
    } else {
      expression += character.replace(/[|\\{}()[\]^$+?.]/gu, "\\$&");
    }
  }
  return new RegExp(`${expression}$`, "u");
}

const pathGroups = new Map(
  groupNames.map((name) => [name, readPathGroup(name).map((glob) => globRegex(glob))]),
);

function matchingGroups(path) {
  const normalized = path.replaceAll("\\", "/");
  return groupNames.filter((name) => pathGroups.get(name).some((pattern) => pattern.test(normalized)));
}

function expectGroups(path, expected) {
  assert.deepEqual(matchingGroups(path), expected, `unexpected CNB lanes for ${path}`);
}

test("curated documentation selects only documentation and knowledge lanes", () => {
  const expected = [".fileweft-docs-paths", ".fileweft-knowledge-paths"];
  expectGroups("fileweft-docs/pages/zh/project/roadmap.md", expected);
  expectGroups("docs/implementation-status.md", expected);
  expectGroups(".ci/README.md", expected);
  expectGroups("README.md", expected);
  expectGroups("AGENTS.md", expected);
  expectGroups("SECURITY.md", expected);
});

test("knowledge indexing excludes historical blueprints and duplicate English pages", () => {
  expectGroups(".ai/FileWeft_Ultimate_Implementation_Manual_COMPLETE/13_AI_AGENT.md", [
    ".fileweft-docs-paths",
  ]);
  expectGroups("fileweft-docs/pages/en/project/roadmap.md", [".fileweft-docs-paths"]);
  expectGroups(".cnb/settings.yml", [
    ".fileweft-knowledge-paths",
    ".fileweft-fast-paths",
  ]);
  expectGroups(".ci/knowledge.yml", [
    ".fileweft-knowledge-paths",
    ".fileweft-fast-paths",
  ]);
  assert.ok(
    !matchingGroups("fileweft-core/src/main/kotlin/Identifier.kt").includes(
      ".fileweft-knowledge-paths",
    ),
    "ordinary source changes must not rebuild the repository knowledge base",
  );
});

test("repository attributes select every lane whose checked-out bytes they can change", () => {
  expectGroups(".gitattributes", groupNames);
});

test("Build Logic tests remain in fast feedback while production conventions expand verification", () => {
  expectGroups("build-logic/src/test/kotlin/FixtureTest.kt", [".fileweft-fast-paths"]);
  expectGroups("build-logic/src/main/kotlin/FileWeftConventionPlugin.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-postgres-paths",
    ".fileweft-mysql-paths",
    ".fileweft-kingbase-paths",
    ".fileweft-rustfs-paths",
    ".fileweft-e2e-paths",
    ".fileweft-release-artifact-paths",
  ]);
});

test("Gradle lock files select every environment whose build graph they can change", () => {
  const expected = [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-postgres-paths",
    ".fileweft-mysql-paths",
    ".fileweft-kingbase-paths",
    ".fileweft-rustfs-paths",
    ".fileweft-e2e-paths",
    ".fileweft-release-artifact-paths",
  ];
  expectGroups("settings-gradle.lockfile", expected);
  expectGroups("build-logic/gradle.lockfile", expected);
});

test("database, storage, and Boot generations select their real dependency closures", () => {
  expectGroups("fileweft-metadata-runtime/src/main/kotlin/MetadataValidator.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-e2e-paths",
  ]);
  expectGroups("fileweft-persistence/src/main/kotlin/JdbcDocumentRepository.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-postgres-paths",
    ".fileweft-mysql-paths",
    ".fileweft-kingbase-paths",
    ".fileweft-e2e-paths",
  ]);
  expectGroups("fileweft-adapter-s3/src/main/kotlin/S3StorageAdapter.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-rustfs-paths",
    ".fileweft-e2e-paths",
  ]);
  expectGroups("fileweft-spring-boot2-starter/src/main/kotlin/FileWeftAutoConfiguration.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm-all-paths",
  ]);
  expectGroups(
    "fileweft-spring-boot2-starter/src/main/kotlin/ai/icen/fw/starter/boot2/FileWeftKingbaseFlywayAutoConfiguration.kt",
    [
      ".fileweft-fast-paths",
      ".fileweft-jvm8-paths",
      ".fileweft-jvm-all-paths",
      ".fileweft-kingbase-paths",
    ],
  );
  expectGroups(
    "fileweft-spring-boot2-starter/src/main/kotlin/ai/icen/fw/starter/boot2/FileWeftMigrationConfiguration.kt",
    [
      ".fileweft-fast-paths",
      ".fileweft-jvm8-paths",
      ".fileweft-jvm-all-paths",
      ".fileweft-kingbase-paths",
    ],
  );
  expectGroups(
    "fileweft-spring-boot3-starter/src/main/kotlin/ai/icen/fw/starter/boot3/FileWeftMigrationConfiguration.kt",
    [
      ".fileweft-fast-paths",
      ".fileweft-jvm17-paths",
      ".fileweft-jvm-all-paths",
      ".fileweft-kingbase-paths",
      ".fileweft-e2e-paths",
    ],
  );
  expectGroups(
    "fileweft-spring-boot3-starter/src/test/kotlin/ai/icen/fw/starter/boot3/KingbaseFlywayAutoConfigurationIntegrationTest.kt",
    [
      ".fileweft-fast-paths",
      ".fileweft-jvm17-paths",
      ".fileweft-jvm-all-paths",
      ".fileweft-kingbase-paths",
    ],
  );
  expectGroups("fileweft-adapter-micrometer/src/main/kotlin/FileWeftMetrics.kt", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-e2e-paths",
  ]);
});

test("runner and wrapper changes select only the environments they can affect", () => {
  expectGroups("gradlew", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-postgres-paths",
    ".fileweft-mysql-paths",
    ".fileweft-kingbase-paths",
    ".fileweft-rustfs-paths",
    ".fileweft-e2e-paths",
    ".fileweft-release-artifact-paths",
  ]);
  expectGroups(".ci/Dockerfile.jvm", [
    ".fileweft-fast-paths",
    ".fileweft-jvm8-paths",
    ".fileweft-jvm17-paths",
    ".fileweft-jvm-all-paths",
    ".fileweft-postgres-paths",
    ".fileweft-mysql-paths",
    ".fileweft-kingbase-paths",
    ".fileweft-rustfs-paths",
    ".fileweft-release-artifact-paths",
  ]);
  expectGroups(".ci/Dockerfile.e2e", [".fileweft-e2e-paths"]);
  expectGroups(".dockerignore", [".fileweft-e2e-paths"]);
  expectGroups(".docker/Dockerfile.dev", [".fileweft-e2e-paths"]);
  expectGroups(".ci/scripts/prepare-kingbase-image.sh", [
    ".fileweft-fast-paths",
    ".fileweft-kingbase-paths",
  ]);
  expectGroups(".ci/scripts/prepare-kingbase-image.ps1", [
    ".fileweft-fast-paths",
    ".fileweft-kingbase-paths",
  ]);
});

test("release inputs run the artifact contract without expanding unrelated suites", () => {
  for (const path of ["release-smoke/build.gradle.kts", "LICENSE", "NOTICE"]) {
    expectGroups(path, [".fileweft-release-artifact-paths"]);
  }
});

test("stable migration fixtures select verification and release lanes", () => {
  expectGroups(".ci/fixtures/postgres-v0.0.1-ai.icen.sha256", [
    ".fileweft-fast-paths",
    ".fileweft-postgres-paths",
    ".fileweft-release-artifact-paths",
  ]);
});

test("PR and main expose the path-scoped release artifact lane", () => {
  for (const [name, configuration] of [
    ["PR", prConfiguration],
    ["main", mainConfiguration],
  ]) {
    assert.ok(
      configuration.includes("ifModify: !reference [.fileweft-release-artifact-paths]"),
      `${name} must scope the artifact lane with the shared path policy`,
    );
    assert.ok(
      configuration.includes(
        "bash ./gradlew releaseArtifactCheck --no-daemon --no-configuration-cache --stacktrace",
      ),
      `${name} must execute the release artifact contract`,
    );
  }
});

test("the repository knowledge base is main-only, curated, and fail-closed", () => {
  assert.ok(
    repositoryConfiguration.includes("- .ci/knowledge.yml"),
    "the root CNB configuration must include the knowledge pipeline",
  );
  assert.match(knowledgeConfiguration, /^main:\r?\n  push:\r?\n/mu);
  assert.doesNotMatch(
    knowledgeConfiguration,
    /pull_request/u,
    "PR content must never update the canonical repository knowledge base",
  );
  assert.match(knowledgeConfiguration, /type: knowledge:update/u);
  assert.match(
    knowledgeConfiguration,
    /ifModify: !reference \[\.fileweft-knowledge-paths\]/u,
  );
  assert.match(knowledgeConfiguration, /issueSyncEnabled: false/u);
  assert.match(knowledgeConfiguration, /forceRebuild: false/u);
  assert.match(knowledgeConfiguration, /ignoreProcessFailures: false/u);
  for (const source of [
    "README.md",
    "AGENTS.md",
    "SECURITY.md",
    ".ci/README.md",
    "docs/**/*.md",
    "fileweft-docs/pages/zh/**/*.md",
  ]) {
    assert.ok(knowledgeConfiguration.includes(`- ${source}`), `missing knowledge source ${source}`);
  }
  for (const excluded of [
    ".ai/**",
    "fileweft-docs/pages/en/**",
    "SKILL.md",
    "fileweft-docs/SKILL.md",
  ]) {
    assert.ok(
      knowledgeConfiguration.includes(`- ${excluded}`),
      `missing knowledge exclusion ${excluded}`,
    );
  }
});

test("the default repository NPC keeps personality subordinate to evidence", () => {
  for (const expected of [
    "name: 织澜",
    "defaultRepo: \"china.ai/file-weft\"",
    "defaultRole: 织澜",
    "name: 问织澜",
    "你不是 FileWeft Agent 产品能力",
    "AGENTS.md",
    "仓库中暂无依据",
    "专业性和事实永远优先于人设",
  ]) {
    assert.ok(repositorySettings.includes(expected), `missing NPC guardrail: ${expected}`);
  }
});

test("release-critical and Docker-context paths never select zero lanes", () => {
  const criticalPaths = [
    "release-smoke/boot2-consumer/src/test/kotlin/ConsumerApplicationTest.kt",
    "LICENSE",
    "NOTICE",
    ".dockerignore",
    ".gitattributes",
    "settings-gradle.lockfile",
    "build-logic/gradle.lockfile",
  ];
  for (const path of criticalPaths) {
    assert.ok(matchingGroups(path).length > 0, `${path} must select at least one CNB lane`);
  }
});

test("release publication destroys its token on both success and failure paths", () => {
  assert.ok(
    releaseConfiguration.includes("name: validate-stable-release-tag"),
    "release publication must reject non-stable tags before waiting for verification lanes",
  );
  assert.ok(
    releaseConfiguration.includes("^v[0-9]+\\.[0-9]+\\.[0-9]+$"),
    "release publication must require an exact numeric vX.Y.Z tag",
  );
  assert.equal(
    releaseConfiguration.match(/type: cnb:destroy-token/gu)?.length,
    2,
    "release publication must keep one normal and one failure-path token cleanup",
  );
  assert.match(
    releaseConfiguration,
    /failStages:\r?\n\s+- name: destroy-publication-token-after-failure\r?\n\s+type: cnb:destroy-token/u,
  );
  const normalCleanup = releaseConfiguration.indexOf("name: destroy-publication-token");
  const coldConsumer = releaseConfiguration.indexOf("name: cold-remote-consumer");
  assert.notEqual(normalCleanup, -1, "release publication must keep its normal token cleanup");
  assert.notEqual(coldConsumer, -1, "release publication must keep the anonymous consumer check");
  assert.ok(
    normalCleanup < coldConsumer,
    "the normal publication token must be destroyed before anonymous remote resolution",
  );
});

test("every tag pipeline rejects non-stable tags before expensive work", () => {
  const pipelineNames = [
    "quality",
    "java8",
    "java11",
    "java17",
    "java21",
    "java25",
    "postgres",
    "mysql",
    "kingbase",
    "rustfs",
    "acceptance",
    "publish",
  ];
  for (const pipelineName of pipelineNames) {
    const pipelineStartPattern = new RegExp(`^    ${pipelineName}:\\r?$`, "mu");
    const pipelineStart = releaseConfiguration.search(pipelineStartPattern);
    assert.notEqual(pipelineStart, -1, `missing release pipeline ${pipelineName}`);
    const remaining = releaseConfiguration.slice(pipelineStart + 1);
    const nextPipelineOffset = remaining.search(/^    [a-z][a-z0-9]*:\r?$/mu);
    const pipeline = releaseConfiguration.slice(
      pipelineStart,
      nextPipelineOffset < 0 ? undefined : pipelineStart + 1 + nextPipelineOffset,
    );
    assert.match(
      pipeline,
      /stages:\r?\n        - \*fileweft-stable-release-tag/u,
      `${pipelineName} must validate the stable tag as its first stage`,
    );
  }
});

test("the Maven publication lock is acquired just in time and outlives the upload job timeout", () => {
  assert.equal(
    releaseConfiguration.match(/^\s+lock:\r?$/gmu)?.length,
    1,
    "release must keep exactly one lock, on the artifact publication job",
  );
  assert.match(
    releaseConfiguration,
    /- name: publish-verified-artifacts\r?\n\s+timeout: 2h\r?\n\s+lock:\r?\n\s+key: fileweft-maven-release\r?\n\s+wait: true\r?\n\s+timeout: 7200\r?\n\s+expires: 10800\r?\n\s+script:/u,
  );
  const awaitStage = releaseConfiguration.indexOf("name: await-release-verification");
  const lock = releaseConfiguration.indexOf("key: fileweft-maven-release");
  const coldConsumer = releaseConfiguration.indexOf("name: cold-remote-consumer");
  assert.ok(awaitStage < lock, "verification must finish before the publication lock is acquired");
  assert.ok(lock < coldConsumer, "the anonymous cold consumer must run after the locked upload job");
});

test("Kingbase preparation and Compose share one fail-closed image identity", () => {
  const image = "kingbase_v008r006c009b0014_single_x86:v1";
  const imageId = "c9e9fdb309b6b18f022e16a8cc4ea91108bf1e609e3ac134e0050a82a01ed5d9";
  const archiveSha256 = "b95e6c39b9a93f3a37354d8f91f78990c99ce9735503210eea14553e92e82595";

  for (const script of [kingbaseBash.toLowerCase(), kingbasePowerShell.toLowerCase()]) {
    assert.ok(script.includes(image), "Kingbase helper must pin the Compose image tag");
    assert.ok(script.includes(imageId), "Kingbase helper must verify the loaded Docker image ID");
    assert.ok(script.includes(archiveSha256), "Kingbase helper must verify the archive SHA-256");
  }
  assert.match(composeConfiguration, new RegExp(`image: ${image}`, "u"));
  assert.match(composeConfiguration, /pull_policy: never/u);
  assert.match(composeConfiguration, /profiles: \["kingbase"\]/u);
  assert.doesNotMatch(
    composeConfiguration,
    /docker\.cnb\.cool\/[^\n]*kingbase/iu,
    "FileWeft must not silently redistribute the commercial Kingbase image",
  );
});
