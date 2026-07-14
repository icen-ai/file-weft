import { createHash } from "node:crypto";

export function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
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
