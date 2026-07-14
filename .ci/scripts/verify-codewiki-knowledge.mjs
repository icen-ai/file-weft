import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { isBoundToGeneratedWiki } from "./codewiki-evidence.mjs";

const requiredEnvironment = [
  "CNB_API_ENDPOINT",
  "CNB_BUILD_WORKSPACE",
  "CNB_REPO_SLUG",
  "CNB_TOKEN",
  "CNB_COMMIT",
];
for (const name of requiredEnvironment) {
  if (!process.env[name]) {
    throw new Error(`Missing required CNB environment variable: ${name}`);
  }
}

const expectedSha = process.env.CNB_COMMIT;
if (!/^[0-9a-f]{40}$/u.test(expectedSha)) {
  throw new Error("CNB_COMMIT must be the exact 40-character lowercase commit SHA.");
}

const contract = JSON.parse(
  await readFile(new URL("../code-knowledge-acceptance.json", import.meta.url), "utf8"),
);
if (contract.repository !== process.env.CNB_REPO_SLUG) {
  throw new Error(
    `Acceptance repository ${contract.repository} does not match ${process.env.CNB_REPO_SLUG}.`,
  );
}

const baseUrl = `${process.env.CNB_API_ENDPOINT.replace(/\/$/u, "")}/${process.env.CNB_REPO_SLUG}`;
const headers = {
  Accept: "application/json",
  Authorization: `Bearer ${process.env.CNB_TOKEN}`,
};
const maximumAttempts = 12;
const retryDelayMilliseconds = 30_000;
const generatedWikiDirectory = join(
  process.env.CNB_BUILD_WORKSPACE,
  process.env.CNB_REPO_SLUG,
  "codewiki",
);

class KnowledgeVerificationError extends Error {
  constructor(message, retryable = true) {
    super(message);
    this.retryable = retryable;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function listMarkdownFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await listMarkdownFiles(path)));
    else if (entry.isFile() && entry.name.endsWith(".md")) files.push(path);
  }
  return files;
}

async function readGeneratedWiki() {
  const status = JSON.parse(await readFile(join(generatedWikiDirectory, "wiki_status.json"), "utf8"));
  if (status.status !== "success") {
    throw new Error(`Generated CodeWiki status is ${status.status ?? "missing"}, not success.`);
  }

  const files = await listMarkdownFiles(generatedWikiDirectory);
  if (files.length === 0) throw new Error("Generated CodeWiki contains no Markdown documents.");

  const documents = [];
  for (const file of files) {
    const content = await readFile(file, "utf8");
    documents.push(content);
  }
  return documents;
}

async function request(path, searchParameters = {}) {
  const url = new URL(`${baseUrl}${path}`);
  for (const [name, value] of Object.entries(searchParameters)) {
    url.searchParams.set(name, String(value));
  }
  const response = await fetch(url, { headers });
  if (!response.ok) {
    const trace = response.headers.get("traceparent") ?? "unavailable";
    const retryable = response.status === 404 || response.status === 429 || response.status >= 500;
    throw new KnowledgeVerificationError(
      `CNB knowledge request failed with HTTP ${response.status}; trace=${trace}.`,
      retryable,
    );
  }
  return response.json();
}

async function verify(generatedDocuments) {
  const knowledgeBase = await request("/-/knowledge/base");
  if (knowledgeBase.last_commit_sha !== expectedSha) {
    throw new KnowledgeVerificationError(
      `Knowledge base SHA ${knowledgeBase.last_commit_sha ?? "missing"} does not match ${expectedSha}.`,
    );
  }

  const verified = [];
  for (const acceptanceCase of contract.cases) {
    const generatedEvidence = generatedDocuments.join("\n");
    for (const anchor of acceptanceCase.requiredAnchors) {
      if (!generatedEvidence.includes(anchor)) {
        throw new KnowledgeVerificationError(
          `${acceptanceCase.id} is missing required anchor from this run's generated Wiki: ${anchor}`,
          false,
        );
      }
    }

    const results = await request("/-/knowledge/base/query", {
      query: acceptanceCase.query,
      top_k: 20,
      score_threshold: 0,
    });
    const currentCodeWikiResults = results.filter((result) =>
      isBoundToGeneratedWiki(result, generatedDocuments),
    );
    if (currentCodeWikiResults.length === 0) {
      throw new KnowledgeVerificationError(
        `${acceptanceCase.id} returned no CodeWiki evidence bound to this run's generated documents.`,
      );
    }
    const evidence = currentCodeWikiResults
      .map((result) => `${result.chunk ?? ""}\n${result.metadata?.path ?? ""}`)
      .join("\n");
    for (const anchor of acceptanceCase.requiredAnchors) {
      if (!evidence.includes(anchor)) {
        throw new KnowledgeVerificationError(
          `${acceptanceCase.id} is missing required CodeWiki anchor: ${anchor}`,
        );
      }
    }
    verified.push(
      `Verified ${acceptanceCase.id}: ${acceptanceCase.requiredAnchors.join(", ")} (${currentCodeWikiResults.length} current chunks).`,
    );
  }
  return verified;
}

const generatedDocuments = await readGeneratedWiki();
for (let attempt = 1; attempt <= maximumAttempts; attempt += 1) {
  try {
    const verified = await verify(generatedDocuments);
    for (const message of verified) console.log(message);
    console.log(`Verified FileWeft CodeWiki knowledge for ${expectedSha}.`);
    break;
  } catch (failure) {
    if (!(failure instanceof KnowledgeVerificationError) || !failure.retryable) throw failure;
    if (attempt === maximumAttempts) throw failure;
    console.warn(
      `CodeWiki evidence is not consistent yet (${attempt}/${maximumAttempts}): ${failure.message}`,
    );
    await delay(retryDelayMilliseconds);
  }
}
