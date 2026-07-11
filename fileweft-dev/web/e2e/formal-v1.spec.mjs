import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

const DOCUMENT_FIELDS = [
  "createdTime",
  "currentVersionId",
  "documentNumber",
  "folderId",
  "id",
  "lifecycleState",
  "title",
  "updatedTime",
];
const VERSION_FIELDS = [
  "contentLength",
  "contentType",
  "createdTime",
  "fileName",
  "id",
  "updatedTime",
  "versionNumber",
];
const ENVELOPE_FIELDS = ["code", "data", "error", "message", "traceId"];
const INTERNAL_FIELDS = new Set([
  "assetId",
  "bucket",
  "contentHash",
  "fileAssetId",
  "fileObjectId",
  "objectKey",
  "storagePath",
  "storageUrl",
  "tenantId",
]);

const sortedKeys = (value) => Object.keys(value).sort();

function authorization(token, traceId) {
  return {
    Authorization: `Bearer ${token}`,
    "X-Trace-Id": traceId,
  };
}

function header(response, name) {
  const normalizedName = name.toLowerCase();
  const entry = Object.entries(response.headers()).find(([candidate]) => candidate.toLowerCase() === normalizedName);
  return entry?.[1];
}

function internalFieldPaths(value, path = "$") {
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => internalFieldPaths(item, `${path}[${index}]`));
  }
  if (value === null || typeof value !== "object") return [];

  return Object.entries(value).flatMap(([key, child]) => {
    const childPath = `${path}.${key}`;
    return [
      ...(INTERNAL_FIELDS.has(key) ? [childPath] : []),
      ...internalFieldPaths(child, childPath),
    ];
  });
}

async function login(request, username, password) {
  const response = await request.post("/api/auth/login", { data: { username, password } });
  expect(response.status(), await response.text()).toBe(200);
  const identity = await response.json();
  expect(identity.token).toEqual(expect.any(String));
  expect(identity.token).not.toHaveLength(0);
  return identity;
}

async function success(response, expectedStatus) {
  expect(response.status(), await response.text()).toBe(expectedStatus);
  expect(header(response, "Content-Type")).toMatch(/^application\/json\b/i);
  const envelope = await response.json();
  expect(sortedKeys(envelope)).toEqual(ENVELOPE_FIELDS);
  expect(envelope.code).toBe("OK");
  expect(envelope.message).toBe("OK");
  expect(envelope.error).toBeNull();
  expect(envelope.traceId).toEqual(expect.any(String));
  expect(envelope.traceId).not.toHaveLength(0);
  expect(internalFieldPaths(envelope)).toEqual([]);
  return envelope.data;
}

async function failure(response, expectedStatus, code, message) {
  expect(response.status(), await response.text()).toBe(expectedStatus);
  expect(header(response, "Content-Type")).toMatch(/^application\/json\b/i);
  const envelope = await response.json();
  expect(sortedKeys(envelope)).toEqual(ENVELOPE_FIELDS);
  expect(envelope).toMatchObject({
    code,
    message,
    data: null,
    error: { code, message },
  });
  expect(sortedKeys(envelope.error)).toEqual(["code", "message"]);
  expect(envelope.traceId).toEqual(expect.any(String));
  expect(envelope.traceId).not.toHaveLength(0);
  expect(internalFieldPaths(envelope)).toEqual([]);
  return envelope;
}

async function createDraft(request, token, traceId, { documentNumber, title, fileName, content }) {
  return request.post("/fileweft/v1/documents", {
    headers: authorization(token, traceId),
    multipart: {
      documentNumber,
      title,
      folderId: "contracts",
      file: {
        name: fileName,
        mimeType: "text/plain",
        buffer: content,
      },
    },
  });
}

async function assertDownload(response, expectedContent, expectedFileName) {
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.body();
  expect(body.equals(expectedContent)).toBe(true);
  expect(header(response, "Content-Type")).toBe("text/plain");
  expect(header(response, "Content-Length")).toBe(String(expectedContent.length));
  expect(header(response, "Cache-Control")).toBe("private, no-store");
  expect(header(response, "X-Content-Type-Options")).toBe("nosniff");

  const disposition = header(response, "Content-Disposition");
  expect(disposition).toMatch(/^attachment; /);
  expect(disposition).toContain("filename=\"");
  expect(disposition).toContain(`filename*=UTF-8''${encodeURIComponent(expectedFileName)}`);
  expect([...disposition].every((character) => {
    const code = character.charCodeAt(0);
    return code >= 0x20 && code <= 0x7e;
  })).toBe(true);

  for (const forbidden of ["ETag", "Accept-Ranges", "Content-Range", "Location"]) {
    expect(header(response, forbidden)).toBeUndefined();
  }
  expect(Object.keys(response.headers()).filter((name) => {
    const normalized = name.toLowerCase();
    return normalized.includes("hash") || normalized.includes("storage") || normalized.includes("bucket") ||
      normalized.includes("object-key");
  })).toEqual([]);
}

test("formal v1 shares one authorized, tenant-isolated document with the Dev proof projection", async ({ request }) => {
  const nonce = `${Date.now()}-${randomUUID().replaceAll("-", "").slice(0, 12)}`;
  const traceId = `playwright-formal-v1-${nonce}`;
  const documentNumber = `PW-V1-${nonce}`;
  const originalTitle = `正式 v1 验收文档 ${nonce}`;
  const renamedTitle = `正式 v1 已重命名 ${nonce}`;
  const historicalFileName = `正式历史-${nonce}.txt`;
  const currentFileName = `正式当前-${nonce}.txt`;
  const historicalContent = Buffer.from(`FileWeft 正式 v1 历史字节 ${nonce}\n`, "utf8");
  const currentContent = Buffer.from(`FileWeft 正式 v1 当前字节 ${nonce}\n`, "utf8");

  const [editor, viewer, betaEditor] = await Promise.all([
    login(request, "editor@alpha", "dev-editor"),
    login(request, "viewer@alpha", "dev-viewer"),
    login(request, "editor@beta", "dev-editor"),
  ]);
  expect(editor).toMatchObject({ userId: "alpha-editor", tenantId: "alpha", role: "EDITOR" });
  expect(viewer).toMatchObject({ userId: "alpha-viewer", tenantId: "alpha", role: "VIEWER" });
  expect(betaEditor).toMatchObject({ userId: "beta-editor", tenantId: "beta", role: "EDITOR" });

  const createdResponse = await createDraft(request, editor.token, traceId, {
    documentNumber,
    title: originalTitle,
    fileName: historicalFileName,
    content: historicalContent,
  });
  const created = await success(createdResponse, 201);
  expect(sortedKeys(created)).toEqual(["documentId", "versionId"]);
  expect(created.documentId).toEqual(expect.any(String));
  expect(created.versionId).toEqual(expect.any(String));
  expect(header(createdResponse, "Location")).toBe(`/fileweft/v1/documents/${created.documentId}`);
  const { documentId, versionId: historicalVersionId } = created;

  const viewerWrite = await request.patch(`/fileweft/v1/documents/${documentId}`, {
    headers: authorization(viewer.token, traceId),
    data: { title: "viewer must not rename" },
  });
  await failure(viewerWrite, 403, "FORBIDDEN", "Access denied.");

  const duplicateResponse = await createDraft(request, editor.token, traceId, {
    documentNumber,
    title: "duplicate must conflict",
    fileName: `duplicate-${nonce}.txt`,
    content: Buffer.from(`duplicate-${nonce}`, "utf8"),
  });
  await failure(duplicateResponse, 409, "CONFLICT", "Request conflicts with the current resource state.");

  const initialDetailResponse = await request.get(`/fileweft/v1/documents/${documentId}`, {
    headers: authorization(editor.token, traceId),
  });
  const initialDetail = await success(initialDetailResponse, 200);
  expect(sortedKeys(initialDetail)).toEqual(["document", "versions"]);
  expect(sortedKeys(initialDetail.document)).toEqual(DOCUMENT_FIELDS);
  expect(initialDetail.document).toMatchObject({
    id: documentId,
    documentNumber,
    title: originalTitle,
    lifecycleState: "DRAFT",
    currentVersionId: historicalVersionId,
    folderId: "contracts",
  });
  expect(initialDetail.versions).toHaveLength(1);
  expect(sortedKeys(initialDetail.versions[0])).toEqual(VERSION_FIELDS);
  expect(initialDetail.versions[0]).toMatchObject({
    id: historicalVersionId,
    versionNumber: "1.0",
    fileName: historicalFileName,
    contentType: "text/plain",
    contentLength: historicalContent.length,
  });

  const pageResponse = await request.get("/fileweft/v1/documents", {
    headers: authorization(editor.token, traceId),
    params: { folderId: "contracts", lifecycleState: "DRAFT", limit: 100 },
  });
  const page = await success(pageResponse, 200);
  expect(sortedKeys(page)).toEqual(["items", "nextCursor", "total"]);
  const pageDocument = page.items.find((candidate) => candidate.id === documentId);
  expect(pageDocument).toBeDefined();
  expect(sortedKeys(pageDocument)).toEqual(DOCUMENT_FIELDS);
  expect(pageDocument).toMatchObject({ documentNumber, title: originalTitle, folderId: "contracts" });

  const renamedResponse = await request.patch(`/fileweft/v1/documents/${documentId}`, {
    headers: authorization(editor.token, traceId),
    data: { title: renamedTitle },
  });
  const renamed = await success(renamedResponse, 200);
  expect(sortedKeys(renamed)).toEqual(["documentId", "versionId"]);
  expect(renamed).toEqual({ documentId, versionId: null });
  expect(header(renamedResponse, "Location")).toBeUndefined();

  const addedVersionResponse = await request.post(`/fileweft/v1/documents/${documentId}/versions`, {
    headers: authorization(editor.token, traceId),
    multipart: {
      versionNumber: "2.0",
      file: {
        name: currentFileName,
        mimeType: "text/plain",
        buffer: currentContent,
      },
    },
  });
  const addedVersion = await success(addedVersionResponse, 201);
  expect(sortedKeys(addedVersion)).toEqual(["documentId", "versionId"]);
  expect(addedVersion.documentId).toBe(documentId);
  expect(addedVersion.versionId).toEqual(expect.any(String));
  expect(addedVersion.versionId).not.toBe(historicalVersionId);
  expect(header(addedVersionResponse, "Location")).toBe(`/fileweft/v1/documents/${documentId}`);
  const currentVersionId = addedVersion.versionId;

  const finalDetailResponse = await request.get(`/fileweft/v1/documents/${documentId}`, {
    headers: authorization(editor.token, traceId),
  });
  const finalDetail = await success(finalDetailResponse, 200);
  expect(sortedKeys(finalDetail)).toEqual(["document", "versions"]);
  expect(sortedKeys(finalDetail.document)).toEqual(DOCUMENT_FIELDS);
  expect(finalDetail.document).toMatchObject({
    id: documentId,
    documentNumber,
    title: renamedTitle,
    lifecycleState: "DRAFT",
    currentVersionId,
    folderId: "contracts",
  });
  expect(finalDetail.versions).toHaveLength(2);
  for (const version of finalDetail.versions) expect(sortedKeys(version)).toEqual(VERSION_FIELDS);
  expect(new Set(finalDetail.versions.map((version) => version.id))).toEqual(
    new Set([historicalVersionId, currentVersionId]),
  );

  const currentContentPath = `/fileweft/v1/documents/${documentId}/content`;
  const historicalContentPath = `/fileweft/v1/documents/${documentId}/versions/${historicalVersionId}/content`;
  const currentDownload = await request.get(currentContentPath, { headers: authorization(editor.token, traceId) });
  await assertDownload(currentDownload, currentContent, currentFileName);
  const historicalDownload = await request.get(historicalContentPath, { headers: authorization(editor.token, traceId) });
  await assertDownload(historicalDownload, historicalContent, historicalFileName);

  for (const path of [currentContentPath, historicalContentPath]) {
    const rangeResponse = await request.get(path, {
      headers: { ...authorization(editor.token, traceId), Range: "bytes=0-2" },
    });
    await failure(rangeResponse, 416, "RANGE_NOT_SUPPORTED", "Range requests are not supported.");
    expect(header(rangeResponse, "Cache-Control")).toBe("private, no-store");
    expect(header(rangeResponse, "X-Content-Type-Options")).toBe("nosniff");
    expect(header(rangeResponse, "Accept-Ranges")).toBeUndefined();

    const headResponse = await request.fetch(path, {
      method: "HEAD",
      headers: authorization(editor.token, traceId),
    });
    expect(headResponse.status()).toBe(405);
    expect(header(headResponse, "Allow")).toBe("GET");
    expect(header(headResponse, "Cache-Control")).toBe("private, no-store");
    expect(header(headResponse, "X-Content-Type-Options")).toBe("nosniff");
    expect(header(headResponse, "Accept-Ranges")).toBeUndefined();
    expect((await headResponse.body()).length).toBe(0);
  }

  const betaDetail = await request.get(`/fileweft/v1/documents/${documentId}`, {
    headers: authorization(betaEditor.token, traceId),
  });
  await failure(betaDetail, 404, "NOT_FOUND", "Resource was not found.");
  const betaPageResponse = await request.get("/fileweft/v1/documents", {
    headers: authorization(betaEditor.token, traceId),
    params: { limit: 100 },
  });
  const betaPage = await success(betaPageResponse, 200);
  expect(betaPage.items.some((candidate) => candidate.id === documentId)).toBe(false);
  const betaDownload = await request.get(currentContentPath, {
    headers: authorization(betaEditor.token, traceId),
  });
  await failure(betaDownload, 404, "NOT_FOUND", "Resource was not found.");

  const legacyResponse = await request.get(`/api/documents/${documentId}`, {
    headers: authorization(editor.token, traceId),
  });
  expect(legacyResponse.status(), await legacyResponse.text()).toBe(200);
  const legacy = await legacyResponse.json();
  expect(legacy.document).toMatchObject({
    id: documentId,
    documentNumber,
    title: renamedTitle,
    lifecycleState: "DRAFT",
    currentVersionId,
    folderId: "contracts",
  });
  expect(new Set(legacy.versions.map((version) => version.id))).toEqual(
    new Set([historicalVersionId, currentVersionId]),
  );

  const expectedActors = new Map([
    ["document:create", 1],
    ["document:rename", 1],
    ["document:version:add", 1],
    ["document:download", 2],
  ]);
  for (const [action, expectedCount] of expectedActors) {
    const audits = legacy.audits.filter((audit) => audit.action === action);
    expect(audits).toHaveLength(expectedCount);
    expect(audits.every((audit) => audit.operatorId === "alpha-editor" && audit.operatorName === "Alpha 编辑者")).toBe(true);
  }
});
