import assert from "node:assert/strict";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  collectBoundCodeWikiResults,
  isBoundToGeneratedWiki,
  isWikiPageForBuild,
  isWikiPageForCommit,
  sha256Text,
} from "../scripts/codewiki-evidence.mjs";

const sharedConfiguration = readFileSync(new URL("../.shared.yml", import.meta.url), "utf8");
const knowledgeConfiguration = readFileSync(new URL("../knowledge.yml", import.meta.url), "utf8");
const codeWikiConfiguration = readFileSync(new URL("../codewiki.yml", import.meta.url), "utf8");
const codeWikiSparseCheckout = readFileSync(
  new URL("../codewiki-sparse-checkout", import.meta.url),
  "utf8",
);
const codeKnowledgeAcceptance = JSON.parse(
  readFileSync(new URL("../code-knowledge-acceptance.json", import.meta.url), "utf8"),
);
const codeKnowledgeVerifier = readFileSync(
  new URL("../scripts/verify-codewiki-knowledge.mjs", import.meta.url),
  "utf8",
);
const repositoryConfiguration = readFileSync(new URL("../../.cnb.yml", import.meta.url), "utf8");
const repositorySettings = readFileSync(new URL("../../.cnb/settings.yml", import.meta.url), "utf8");
const webTriggerConfiguration = readFileSync(
  new URL("../../.cnb/web_trigger.yml", import.meta.url),
  "utf8",
);
const lines = `${sharedConfiguration}\n${knowledgeConfiguration}`.split(/\r?\n/u);
const prConfiguration = readFileSync(new URL("../pr.yml", import.meta.url), "utf8");
const mainConfiguration = readFileSync(new URL("../main.yml", import.meta.url), "utf8");
const releaseConfiguration = readFileSync(new URL("../release.yml", import.meta.url), "utf8");
const buildConfiguration = readFileSync(new URL("../../build.gradle.kts", import.meta.url), "utf8");
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

function runGit(workingDirectory, arguments_, options = {}) {
  const result = spawnSync("git", arguments_, {
    cwd: workingDirectory,
    encoding: "utf8",
    ...options,
  });
  assert.equal(
    result.status,
    0,
    `git ${arguments_.join(" ")} failed:\n${result.stderr || result.stdout}`,
  );
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
  for (const path of [
    ".ci/codewiki.yml",
    ".ci/codewiki-sparse-checkout",
    ".ci/code-knowledge-acceptance.json",
    ".ci/scripts/verify-codewiki-knowledge.mjs",
    ".cnb/web_trigger.yml",
  ]) {
    expectGroups(path, [".fileweft-fast-paths"]);
  }
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

test("CodeWiki is manual, source-curated, pinned, serialized, and verified", () => {
  assert.ok(
    repositoryConfiguration.includes("- .ci/codewiki.yml"),
    "the root CNB configuration must include the manual CodeWiki pipeline",
  );
  assert.match(codeWikiConfiguration, /^main:\r?\n  web_trigger_codewiki:/mu);
  assert.doesNotMatch(codeWikiConfiguration, /\b(?:push|pull_request|tag_push|api_trigger):/u);
  assert.match(codeWikiConfiguration, /runner: !reference \[\.fileweft-runner-4\]/u);
  assert.match(codeWikiConfiguration, /timeout: 4h/u);
  assert.match(
    codeWikiConfiguration,
    /codewiki:v1\.12\.0@sha256:961271592768c7baa914d247ad4f2f37a7f795d386428ffef6684754fd4a7819/u,
  );
  for (const expected of [
    "git sparse-checkout set --no-cone --stdin < .ci/codewiki-sparse-checkout",
    'test "$(git rev-parse HEAD)" = "${CNB_COMMIT}"',
    "test -f AGENTS.md",
    "test -f .ci/scripts/verify-codewiki-knowledge.mjs",
    "test ! -e .ai",
    "test ! -e fileweft-agent",
    "test ! -e codewiki",
    "knowledge_enabled: true",
    "knowledge_embedding_model: hunyuan",
    "knowledge_chunk_size: 1500",
    "knowledge_chunk_overlap: 100",
    "node .ci/scripts/verify-codewiki-knowledge.mjs",
  ]) {
    assert.ok(codeWikiConfiguration.includes(expected), `missing CodeWiki contract: ${expected}`);
  }
  assert.doesNotMatch(
    codeWikiConfiguration,
    /git sparse-checkout init\b/u,
    "sparse-checkout set must bootstrap before the .ci rules file can be pruned",
  );
  assert.match(codeWikiConfiguration, /key: fileweft-knowledge-base/u);
  assert.match(knowledgeConfiguration, /key: fileweft-knowledge-base/u);
  assert.doesNotMatch(codeWikiConfiguration, /cancel-in-(?:wait|progress)/u);
  assert.doesNotMatch(knowledgeConfiguration, /cancel-in-(?:wait|progress)/u);

  for (const excluded of [
    "!/.ai/",
    "!/fileweft-agent/",
    "!/*/src/test/",
    "!/fileweft-docs/pages/en/",
    "!/fileweft-dev/web/fixtures/",
  ]) {
    assert.ok(codeWikiSparseCheckout.includes(excluded), `missing CodeWiki exclusion ${excluded}`);
  }

  assert.equal(codeKnowledgeAcceptance.repository, "china.ai/file-weft");
  assert.ok(codeKnowledgeAcceptance.cases.length >= 3);
  for (const acceptanceCase of codeKnowledgeAcceptance.cases) {
    assert.ok(acceptanceCase.query.length > 0, `${acceptanceCase.id} needs a query`);
    assert.ok(
      acceptanceCase.requiredAnchors.length >= 2,
      `${acceptanceCase.id} needs exact-symbol anchors`,
    );
  }
  assert.ok(
    codeKnowledgeVerifier.includes(
      "collectBoundCodeWikiResults(results, generatedDocuments)",
    ),
  );
  assert.ok(
    codeKnowledgeVerifier.includes("isWikiPageForBuild("),
  );
  assert.ok(codeKnowledgeVerifier.includes('from "./codewiki-evidence.mjs"'));
  assert.ok(codeKnowledgeVerifier.includes('status.status !== "success"'));
  assert.ok(codeKnowledgeVerifier.includes('"CNB_BRANCH"'));
  assert.ok(codeKnowledgeVerifier.includes('"CNB_BUILD_ID"'));
  assert.ok(codeKnowledgeVerifier.includes('"CNB_WEB_ENDPOINT"'));
  assert.doesNotMatch(codeKnowledgeVerifier, /last_commit_sha/u);
  assert.ok(
    codeKnowledgeVerifier.includes(
      "currentCodeWikiResults === null",
    ),
  );
  assert.ok(codeKnowledgeVerifier.includes("const maximumAttempts = 8"));
  assert.ok(codeKnowledgeVerifier.includes("const retryDelayMilliseconds = 30_000"));
  assert.ok(codeKnowledgeVerifier.includes("const requestTimeoutMilliseconds = 10_000"));
  assert.ok(codeKnowledgeVerifier.includes("const queryResultLimit = 5"));

  const attempts = Number(
    codeKnowledgeVerifier.match(/const maximumAttempts = (\d+);/u)?.[1],
  );
  const retryDelay = Number(
    codeKnowledgeVerifier.match(/const retryDelayMilliseconds = ([\d_]+);/u)?.[1].replaceAll("_", ""),
  );
  const requestTimeout = Number(
    codeKnowledgeVerifier.match(/const requestTimeoutMilliseconds = ([\d_]+);/u)?.[1].replaceAll("_", ""),
  );
  const stageMinutes = Number(codeWikiConfiguration.match(/verify-codewiki-knowledge\r?\n\s+timeout: (\d+)m/u)?.[1]);
  const worstCaseNetworkBudget =
    attempts * (1 + codeKnowledgeAcceptance.cases.length) * requestTimeout +
    (attempts - 1) * retryDelay;
  assert.ok(
    worstCaseNetworkBudget <= stageMinutes * 60_000 - 60_000,
    "CodeWiki retry and request timeouts must leave at least one minute of stage headroom",
  );
});

test("the published CodeWiki page must identify the exact build, ref, and commit", () => {
  const commitSha = "dbf2a50fbca41e2ac5b5cf18bb44f9287c153637";
  const buildId = "cnb-4d8-1jtgg1511";
  const page = (pipelineBuildId, ref, pipelineSha, href = true) => {
    const nextData = JSON.stringify({
      props: { pageProps: { pipelineMeta: { buildId: pipelineBuildId, ref, sha: pipelineSha } } },
    });
    const link = href
      ? `<a href="/china.ai/file-weft/-/commits/${commitSha}">dbf2a50f</a>`
      : `/china.ai/file-weft/-/commits/${commitSha}`;
    return `${link}<script id="__NEXT_DATA__" type="application/json" nonce="fixture">${nextData}</script>`;
  };
  const currentPage = page(buildId, "main", commitSha);
  const absolutePage = currentPage.replace(
    'href="/china.ai/file-weft',
    'href="https://cnb.cool/china.ai/file-weft',
  );

  assert.equal(isWikiPageForCommit(currentPage, "china.ai/file-weft", commitSha), true);
  assert.equal(isWikiPageForCommit(absolutePage, "china.ai/file-weft", commitSha), true);
  assert.equal(
    isWikiPageForBuild(currentPage, "china.ai/file-weft", commitSha, buildId, "main"),
    true,
  );
  assert.equal(
    isWikiPageForBuild(absolutePage, "china.ai/file-weft", commitSha, buildId, "main"),
    true,
  );
  assert.equal(
    isWikiPageForBuild(
      page("cnb-stale-build", "main", commitSha),
      "china.ai/file-weft",
      commitSha,
      buildId,
      "main",
    ),
    false,
  );
  assert.equal(
    isWikiPageForBuild(page(buildId, "feature", commitSha), "china.ai/file-weft", commitSha, buildId, "main"),
    false,
  );
  assert.equal(
    isWikiPageForBuild(page(buildId, "main", commitSha, false), "china.ai/file-weft", commitSha, buildId, "main"),
    false,
  );
  assert.equal(isWikiPageForCommit(currentPage, "china.ai/another-repository", commitSha), false);
  assert.equal(
    isWikiPageForCommit(
      currentPage,
      "china.ai/file-weft",
      "f7f1b438be0ef15d772f3452f4804a118bc6e2d3",
    ),
    false,
  );
  assert.equal(isWikiPageForCommit(currentPage, "china.ai/file-weft", "dbf2a50f"), false);
});

test("CodeWiki query evidence must be a hashed token sequence from this run", () => {
  const generatedDocument = `# Resumable uploads

## Reconciliation

ResumableUploadReconciler reads a stable checkpoint before it calls
reconcileCompleting and records an unknown completion result for a later retry.

## Completion rejection

MultipartCompletionRejectedException is retained as deterministic evidence and
must not be collapsed into an ambiguous transport failure.
`;
  const currentChunk = `## Resumable uploads - Reconciliation

ResumableUploadReconciler reads a stable checkpoint before it calls reconcileCompleting
and records an unknown completion result for a later retry.

## Resumable uploads - Completion rejection

MultipartCompletionRejectedException is retained as deterministic evidence and must not
be collapsed into an ambiguous transport failure.`;
  const result = {
    chunk: currentChunk,
    metadata: { type: "codewiki", hash: sha256Text(currentChunk) },
  };
  const staleChunk = currentChunk.replace("stable checkpoint", "mutable state");
  const staleResult = {
    chunk: staleChunk,
    metadata: { type: "codewiki", hash: sha256Text(staleChunk) },
  };

  assert.equal(isBoundToGeneratedWiki(result, [generatedDocument]), true);
  assert.equal(
    isBoundToGeneratedWiki(result, [generatedDocument.replace("stable checkpoint", "mutable state")]),
    false,
  );
  assert.equal(isBoundToGeneratedWiki({ ...result, metadata: { ...result.metadata, hash: "0" } }, [generatedDocument]), false);
  assert.equal(isBoundToGeneratedWiki({ ...result, metadata: { ...result.metadata, type: "code" } }, [generatedDocument]), false);
  assert.deepEqual(collectBoundCodeWikiResults([result], [generatedDocument]), [result]);
  assert.equal(
    collectBoundCodeWikiResults([result, staleResult], [generatedDocument]),
    null,
  );
  assert.equal(collectBoundCodeWikiResults([staleResult], [generatedDocument]), null);
  assert.deepEqual(
    collectBoundCodeWikiResults(
      [{ ...result, metadata: { ...result.metadata, type: "code" } }],
      [generatedDocument],
    ),
    [],
  );
});

test("the CodeWiki sparse rules retain production evidence and remove superseded input", () => {
  const repository = mkdtempSync(join(tmpdir(), "fileweft-codewiki-sparse-"));
  const files = {
    "AGENTS.md": "current decisions\n",
    ".ci/codewiki-sparse-checkout": codeWikiSparseCheckout,
    ".ci/scripts/verify-codewiki-knowledge.mjs": "// verifier\n",
    ".ai/manual.md": "historical blueprint\n",
    "fileweft-agent/src/main/kotlin/LegacyAgent.kt": "class LegacyAgent\n",
    "fileweft-core/src/main/kotlin/Identifier.kt": "class Identifier\n",
    "fileweft-core/src/test/kotlin/IdentifierTest.kt": "class IdentifierTest\n",
    "fileweft-docs/pages/en/guide.md": "duplicate English guide\n",
    "fileweft-docs/pages/zh/guide.md": "current Chinese guide\n",
    "fileweft-dev/web/fixtures/data.json": "{}\n",
  };
  try {
    for (const [path, content] of Object.entries(files)) {
      const absolutePath = join(repository, path);
      mkdirSync(dirname(absolutePath), { recursive: true });
      writeFileSync(absolutePath, content, "utf8");
    }
    runGit(repository, ["init", "--initial-branch=main"]);
    runGit(repository, ["add", "."]);
    runGit(repository, [
      "-c",
      "user.name=FileWeft CI",
      "-c",
      "user.email=ci@invalid.example",
      "commit",
      "-m",
      "fixture",
    ]);
    runGit(repository, ["sparse-checkout", "set", "--no-cone", "--stdin"], {
      input: readFileSync(join(repository, ".ci/codewiki-sparse-checkout"), "utf8"),
    });

    for (const retained of [
      "AGENTS.md",
      ".ci/codewiki-sparse-checkout",
      ".ci/scripts/verify-codewiki-knowledge.mjs",
      "fileweft-core/src/main/kotlin/Identifier.kt",
      "fileweft-docs/pages/zh/guide.md",
    ]) {
      assert.ok(existsSync(join(repository, retained)), `sparse checkout removed ${retained}`);
    }
    for (const excluded of [
      ".ai/manual.md",
      "fileweft-agent/src/main/kotlin/LegacyAgent.kt",
      "fileweft-core/src/test/kotlin/IdentifierTest.kt",
      "fileweft-docs/pages/en/guide.md",
      "fileweft-dev/web/fixtures/data.json",
    ]) {
      assert.ok(!existsSync(join(repository, excluded)), `sparse checkout retained ${excluded}`);
    }
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

test("the source-knowledge button is main-only and restricted to repository administrators", () => {
  assert.match(webTriggerConfiguration, /reg: "\^main\$"/u);
  assert.match(webTriggerConfiguration, /event: web_trigger_codewiki/u);
  assert.match(webTriggerConfiguration, /groupName: 仓库 AI/u);
  assert.match(webTriggerConfiguration, /roles:\r?\n\s+- owner\r?\n\s+- master/u);
  assert.ok(
    readFileSync(new URL("../README.md", import.meta.url), "utf8").includes(
      "permissions.roles` 只在页面检查，不是服务端授权边界",
    ),
  );
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
    "git rev-parse HEAD",
    "相对路径:行号",
    "CodeWiki 不能覆盖源码事实",
    "具体类请在 Issue/PR 中 @织澜核对源码",
    "默认只读",
    "没有执行就不得声称",
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

test("verified release publication is restricted to the current remote main HEAD", () => {
  assert.ok(buildConfiguration.includes('"ls-remote",'));
  assert.ok(buildConfiguration.includes('"refs/heads/main",'));
  assert.ok(buildConfiguration.includes('environment()["GIT_TERMINAL_PROMPT"] = "0"'));
  assert.ok(buildConfiguration.includes("remoteMainCommit == cnbCommit"));
  assert.ok(buildConfiguration.includes("is not the current remote main HEAD"));
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
