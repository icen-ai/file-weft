import { createHash } from "node:crypto";

export function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function isWikiPageForCommit(value, repository, commitSha) {
  if (typeof value !== "string" || typeof repository !== "string") return false;
  if (!/^[0-9a-f]{40}$/u.test(commitSha)) return false;
  if (!/^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)+$/u.test(repository)) return false;

  const commitPath = `/${repository}/-/commits/${commitSha}`;
  const escapedCommitPath = commitPath.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  return new RegExp(
    `href=["'](?:https?:\\/\\/[^/"']+)?${escapedCommitPath}(?:[?#][^"']*)?["']`,
    "u",
  ).test(value);
}

export function readWikiPipelineMeta(value) {
  if (typeof value !== "string") return null;
  const nextData = value.match(
    /<script\b[^>]*\bid=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/u,
  )?.[1];
  if (!nextData) return null;

  try {
    const metadata = JSON.parse(nextData)?.props?.pageProps?.pipelineMeta;
    if (
      typeof metadata?.buildId !== "string" ||
      typeof metadata?.ref !== "string" ||
      typeof metadata?.sha !== "string"
    ) {
      return null;
    }
    return {
      buildId: metadata.buildId,
      ref: metadata.ref,
      sha: metadata.sha,
    };
  } catch {
    return null;
  }
}

export function isWikiPageForBuild(value, repository, commitSha, buildId, ref) {
  if (!isWikiPageForCommit(value, repository, commitSha)) return false;
  const metadata = readWikiPipelineMeta(value);
  return (
    metadata?.sha === commitSha &&
    metadata?.buildId === buildId &&
    metadata?.ref === ref
  );
}

export function evidenceTokens(value) {
  return value.match(/[\p{L}\p{N}_]+|[^\s\p{L}\p{N}_]/gu) ?? [];
}

export function restoreGeneratedSectionHeaders(value, generatedDocument) {
  const heading = generatedDocument.match(/^# (.+)$/mu)?.[1];
  if (!heading) return value;

  const generatedPrefix = `## ${heading} - `;
  const lines = value.replace(/\r\n/gu, "\n").split("\n");
  return lines
    .map((line) =>
      line.startsWith(generatedPrefix) ? `## ${line.slice(generatedPrefix.length)}` : line,
    )
    .join("\n");
}

export function isBoundToGeneratedWiki(result, generatedDocuments) {
  if (result.metadata?.type !== "codewiki" || typeof result.chunk !== "string") return false;
  if (result.metadata?.hash !== sha256Text(result.chunk)) return false;

  return generatedDocuments.some((document) => {
    const chunkTokens = evidenceTokens(restoreGeneratedSectionHeaders(result.chunk, document));
    if (chunkTokens.length < 10) return false;
    const tokenSequence = chunkTokens.join("\u0000");
    return evidenceTokens(document).join("\u0000").includes(tokenSequence);
  });
}

export function collectBoundCodeWikiResults(results, generatedDocuments) {
  const codeWikiResults = results.filter((result) => result.metadata?.type === "codewiki");
  if (codeWikiResults.some((result) => !isBoundToGeneratedWiki(result, generatedDocuments))) {
    return null;
  }
  return codeWikiResults;
}
