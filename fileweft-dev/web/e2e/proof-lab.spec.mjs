import { expect, test } from "@playwright/test";

const presetId = (username) => `login-preset-${username.replace("@", "-")}`;
const uniqueDocumentNumber = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
const DOCTOR_INTERNAL_FIELDS = new Set([
  "tenantId", "evidence", "exceptionType", "errorMessage", "lastError", "folderId", "profileId", "targetId",
  "deliveryId", "externalId", "eventId", "outboxId", "leaseOwner", "leaseToken", "payload", "requestedBy",
  "operatorId", "operatorName", "assetId", "fileAssetId", "fileObjectId", "storagePath", "storageUrl", "bucket",
  "objectKey", "ownerRef", "connectorId",
]);

async function apiLogin(request, username, password) {
  const response = await request.post("/api/auth/login", { data: { username, password } });
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

function internalDoctorFieldPaths(value, path = "$") {
  if (Array.isArray(value)) return value.flatMap((item, index) => internalDoctorFieldPaths(item, `${path}[${index}]`));
  if (value === null || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, child]) => {
    const childPath = `${path}.${key}`;
    return [...(DOCTOR_INTERNAL_FIELDS.has(key) ? [childPath] : []), ...internalDoctorFieldPaths(child, childPath)];
  });
}

function bearer(identity) {
  return { Authorization: `Bearer ${identity.token}` };
}

async function login(page, username) {
  await page.goto("/");
  await expect(page.locator("#login-form")).toBeVisible();

  const preset = page.getByTestId(presetId(username));
  await expect(preset).toHaveCount(1);
  await preset.click();
  await expect(page.locator("#username")).toHaveValue(username);

  const submit = page.locator("#login-form button[type='submit']");
  await expect(submit).toHaveCount(1);
  await submit.click();
  await expect(page.locator("#app-view")).toBeVisible();
}

async function createFixture(page, fixtureId) {
  const fixturesNavigation = page.locator("[data-panel='fixtures']");
  await expect(fixturesNavigation).toHaveCount(1);
  await fixturesNavigation.click();
  await expect(page.locator("#fixtures-panel")).toBeVisible();

  const upload = page.getByTestId(`fixture-upload-${fixtureId}`);
  await expect(upload).toHaveCount(1);
  await upload.click();
  await expect(page.locator("#document-inspector")).toBeVisible();
  await expect(page.locator("#selected-number")).toContainText(`LAB-${fixtureId.toUpperCase()}-`);

  const documentNumber = await page.locator("#selected-number").innerText();
  const documentRow = page.getByTestId("document-row").filter({ hasText: documentNumber });
  await expect(documentRow).toHaveCount(1);
  const documentId = await documentRow.getAttribute("data-document-id");
  expect(documentId).not.toBeNull();
  return { documentId, documentNumber };
}

async function createDocument(page, prefix) {
  const documentNumber = uniqueDocumentNumber(prefix);
  const title = `${prefix} browser acceptance document`;
  const drawer = page.locator("#create-drawer");
  await page.locator("#open-create").click();
  await expect(drawer).toBeVisible();
  await page.locator("#create-form [name='documentNumber']").fill(documentNumber);
  await page.locator("#create-form [name='title']").fill(title);
  await page.locator("#folder-id").selectOption("inbox");
  await page.locator("#create-form [name='file']").setInputFiles({
    name: "frontend-initial.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("FileWeft browser acceptance initial version.", "utf8"),
  });
  await page.locator("#create-form button[type='submit']").click();
  await expect(drawer).toBeHidden();
  await expect(page.locator("#selected-number")).toHaveText(documentNumber);

  const documentRow = page.getByTestId("document-row").filter({ hasText: documentNumber });
  await expect(documentRow).toHaveCount(1);
  const documentId = await documentRow.getAttribute("data-document-id");
  expect(documentId).not.toBeNull();
  return { documentId, documentNumber, title };
}

async function selectDocument(page, documentId) {
  const row = page.locator(`[data-document-id='${documentId}']`);
  await expect(row).toHaveCount(1);
  await row.click();
  await expect(page.locator("#document-inspector")).toBeVisible();
}

test("does not proxy downstream simulator endpoints into the browser origin", async ({ page }) => {
  const response = await page.request.get("/platform/v1/documents");
  expect(response.status()).toBe(404);
});

test("renders Chinese labels and filters the viewer capability surface", async ({ page }) => {
  await page.goto("/");
  const chinese = page.getByTestId("locale-zh");
  await expect(chinese).toHaveCount(1);
  await chinese.click();
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");

  await login(page, "viewer@alpha");
  await expect(page.locator("#identity-name")).toHaveText("Alpha 只读用户");
  await expect(page.locator("#open-create")).toBeHidden();
  await expect(page.locator("#process-outbox")).toBeHidden();
  await expect(page.locator("#process-tasks")).toBeHidden();
  await expect(page.locator("[data-panel='doctor']")).toBeHidden();
  await expect(page.locator("[data-panel='uploads']")).toBeHidden();

  const fixturesNavigation = page.locator("[data-panel='fixtures']");
  await fixturesNavigation.click();
  await expect(page.locator("#fixture-grid .fixture-locked")).toHaveCount(4);
});

test("uploads a real fixture, submits it, and keeps it isolated from Beta", async ({ browser, page }) => {
  await login(page, "editor@alpha");
  await expect(page.locator("#open-create")).toBeVisible();
  await expect(page.locator("#process-outbox")).toBeHidden();

  const created = await createFixture(page, "contract");
  const submit = page.locator("#document-actions [data-action='submit']");
  await expect(submit).toHaveCount(1);
  await submit.click();
  await expect(page.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");
  await expect(page.getByTestId("workflow-inbox-nav")).toBeHidden();

  const betaContext = await browser.newContext();
  try {
    const betaPage = await betaContext.newPage();
    await login(betaPage, "viewer@beta");
    await expect(betaPage.locator("#identity-meta")).toHaveText("beta / viewer@beta");
    await expect(betaPage.locator("#document-list")).not.toContainText(created.documentNumber);
    await expect(betaPage.locator(`[data-document-id='${created.documentId}']`)).toHaveCount(0);
  } finally {
    await betaContext.close();
  }
});

test("exposes administrator processing controls and reviewer approval only to their roles", async ({ browser, page }) => {
  await login(page, "editor@alpha");
  const created = await createFixture(page, "incident");
  const submit = page.locator("#document-actions [data-action='submit']");
  await expect(submit).toHaveCount(1);
  await submit.click();
  await expect(page.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");

  const reviewerContext = await browser.newContext();
  try {
    const reviewerPage = await reviewerContext.newPage();
    await login(reviewerPage, "reviewer@alpha");
    await expect(reviewerPage.getByTestId("workflow-inbox-nav")).toBeVisible();
    await reviewerPage.getByTestId("workflow-inbox-nav").click();
    const taskCard = reviewerPage.locator(`[data-workflow-document='${created.documentId}']`);
    await expect(taskCard).toHaveCount(1);
    await taskCard.getByTestId("workflow-task-approve").click();
    await expect(taskCard).toHaveCount(0);
  } finally {
    await reviewerContext.close();
  }

  const administratorContext = await browser.newContext();
  try {
    const administratorPage = await administratorContext.newPage();
    await login(administratorPage, "admin@alpha");
    await expect(administratorPage.locator("#process-outbox")).toBeVisible();
    await expect(administratorPage.locator("#process-tasks")).toBeVisible();
    await expect(administratorPage.getByTestId("workflow-inbox-nav")).toBeVisible();
    await expect(administratorPage.locator("[data-panel='doctor']")).toBeVisible();
    await expect(administratorPage.locator("[data-panel='uploads']")).toBeVisible();
  } finally {
    await administratorContext.close();
  }
});

test("runs immediate and asynchronous Doctor through formal v1 without rendering internal evidence", async ({ page }) => {
  const doctorPaths = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path.toLowerCase().includes("doctor")) doctorPaths.push(`${request.method()} ${path}`);
  });

  const appSourceResponse = await page.request.get("/app.js");
  expect(appSourceResponse.status()).toBe(200);
  const appSource = await appSourceResponse.text();
  expect(appSource).toContain("`${V1_DOCUMENTS_PATH}/${encodeURIComponent(documentId)}/doctor`");
  expect(appSource).toContain("v1Api(documentDoctorPath(documentId))");
  expect(appSource).toContain("v1Api(`${documentDoctorPath(documentId)}/tasks/${encodeURIComponent(taskId)}`)");
  expect(appSource).toContain("v1Api(`${documentDoctorPath(documentId)}/tasks`, {");

  await login(page, "editor@alpha");
  const created = await createFixture(page, "incident");
  await expect(page.getByTestId("doctor-system-scope")).toBeHidden();

  const editorIdentity = await apiLogin(page.request, "editor@alpha", "dev-editor");
  const legacyGet = await page.request.get(`/api/documents/${created.documentId}/doctor`, { headers: bearer(editorIdentity) });
  const legacyPost = await page.request.post(`/api/documents/${created.documentId}/doctor/tasks`, { headers: bearer(editorIdentity) });
  for (const legacyResponse of [legacyGet, legacyPost]) {
    expect(legacyResponse.status()).toBe(404);
    const legacyBody = await legacyResponse.text();
    for (const field of ["tenantId", "documentId", "taskId", "checks", "checkerName", "reason", "evidence", "repairSuggestion"]) {
      expect(legacyBody).not.toContain(`"${field}"`);
    }
  }

  const immediateResponse = page.waitForResponse((response) =>
    response.request().method() === "GET" && new URL(response.url()).pathname === `/fileweft/v1/documents/${created.documentId}/doctor`,
  );
  await page.locator("#document-actions [data-action='doctor']").click();
  expect((await immediateResponse).status()).toBe(200);
  await expect(page.getByTestId("doctor-panel")).toBeVisible();
  await expect(page.getByTestId("doctor-system-scope")).toBeHidden();
  await expect(page.locator("#doctor-immediate-status")).not.toHaveAttribute("data-doctor-status", "IDLE");
  await expect(page.locator("#doctor-output .doctor-check")).not.toHaveCount(0);
  await expect(page.getByTestId("doctor-check-permission")).toHaveCount(1);
  await expect(page.getByTestId("doctor-check-storage")).toHaveCount(1);

  const scheduledResponse = page.waitForResponse((response) =>
    response.request().method() === "POST" && new URL(response.url()).pathname === `/fileweft/v1/documents/${created.documentId}/doctor/tasks`,
  );
  await page.locator("#schedule-doctor").click();
  const scheduled = await scheduledResponse;
  expect(scheduled.status()).toBe(202);
  const idempotencyHeaders = (await scheduled.request().headersArray())
    .filter((header) => header.name.toLowerCase() === "idempotency-key");
  expect(idempotencyHeaders).toHaveLength(1);
  expect(idempotencyHeaders[0].value).toMatch(/^dev-ui-doctor-[A-Za-z0-9._~:-]+$/);
  await expect(page.locator("#doctor-task-status")).toHaveAttribute("data-doctor-status", "SUCCESS", { timeout: 15_000 });
  await expect(page.locator("#doctor-task-output .doctor-check")).not.toHaveCount(0);

  expect(doctorPaths.length).toBeGreaterThanOrEqual(3);
  expect(doctorPaths.some((path) => path.toLowerCase().includes("/api/") && path.toLowerCase().includes("doctor"))).toBe(false);
  expect(doctorPaths.every((path) => path.includes("/fileweft/v1/"))).toBe(true);
  const panelText = await page.getByTestId("doctor-panel").innerText();
  const panelMarkup = await page.getByTestId("doctor-panel").evaluate((element) => element.outerHTML);
  for (const field of DOCTOR_INTERNAL_FIELDS) {
    expect(panelText).not.toContain(field);
    expect(panelMarkup).not.toContain(field);
  }

  await page.getByTestId("locale-zh").click();
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
  await expect(page.getByTestId("doctor-panel")).toContainText("即时文档诊断");
  await expect(page.getByTestId("doctor-panel")).toContainText("对象存储");

  const viewer = await apiLogin(page.request, "viewer@alpha", "dev-viewer");
  const viewerResponse = await page.request.get(`/fileweft/v1/documents/${created.documentId}/doctor`, { headers: bearer(viewer) });
  expect(viewerResponse.status()).toBe(403);
  expect(internalDoctorFieldPaths(await viewerResponse.json())).toEqual([]);

  const betaReviewer = await apiLogin(page.request, "reviewer@beta", "dev-reviewer");
  const crossTenant = await page.request.get(`/fileweft/v1/documents/${created.documentId}/doctor`, { headers: bearer(betaReviewer) });
  expect(crossTenant.status()).toBe(404);
  expect(internalDoctorFieldPaths(await crossTenant.json())).toEqual([]);
});

test("shows tenant system Doctor only to administrators and keeps both tenant responses redacted", async ({ page }) => {
  const doctorPaths = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path.toLowerCase().includes("doctor")) doctorPaths.push(`${request.method()} ${path}`);
  });

  await login(page, "admin@alpha");
  await page.locator("[data-panel='doctor']").click();
  await expect(page.getByTestId("doctor-system-scope")).toBeVisible();
  const systemResponsePromise = page.waitForResponse((response) =>
    response.request().method() === "GET" && new URL(response.url()).pathname === "/fileweft/v1/doctor",
  );
  await page.locator("#run-system-doctor").click();
  const systemResponse = await systemResponsePromise;
  expect(systemResponse.status()).toBe(200);
  await expect(page.locator("#doctor-system-status")).not.toHaveAttribute("data-doctor-status", "IDLE");
  await expect(page.locator("#doctor-system-output .doctor-check")).not.toHaveCount(0);
  expect(internalDoctorFieldPaths(await systemResponse.json())).toEqual([]);

  const panelText = await page.getByTestId("doctor-panel").innerText();
  const panelMarkup = await page.getByTestId("doctor-panel").evaluate((element) => element.outerHTML);
  expect(panelText.toLowerCase()).not.toContain("alpha");
  expect(panelText.toLowerCase()).not.toContain("beta");
  for (const field of DOCTOR_INTERNAL_FIELDS) {
    expect(panelText).not.toContain(field);
    expect(panelMarkup).not.toContain(field);
  }
  expect(doctorPaths.some((path) => path.toLowerCase().includes("/api/") && path.toLowerCase().includes("doctor"))).toBe(false);

  const betaAdmin = await apiLogin(page.request, "admin@beta", "dev-admin");
  const betaSystem = await page.request.get("/fileweft/v1/doctor", { headers: bearer(betaAdmin) });
  expect(betaSystem.status()).toBe(200);
  const betaPayload = await betaSystem.json();
  expect(internalDoctorFieldPaths(betaPayload)).toEqual([]);
  expect(JSON.stringify(betaPayload).toLowerCase()).not.toContain("beta");

  const editor = await apiLogin(page.request, "editor@alpha", "dev-editor");
  const forbidden = await page.request.get("/fileweft/v1/doctor", { headers: bearer(editor) });
  expect(forbidden.status()).toBe(403);
  expect(internalDoctorFieldPaths(await forbidden.json())).toEqual([]);
});

test("creates, renames, versions, downloads, and moves a document through the editor UI", async ({ page }) => {
  await login(page, "editor@alpha");
  const created = await createDocument(page, "UI-EDIT");

  await page.getByTestId("locale-zh").click();
  await page.locator("#open-create").click();
  await page.locator("#create-form [name='documentNumber']").fill(created.documentNumber);
  await page.locator("#create-form [name='title']").fill("重复编号应展示中文正式接口错误");
  await page.locator("#create-form [name='file']").setInputFiles({
    name: "frontend-duplicate.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("duplicate document number must fail", "utf8"),
  });
  await page.locator("#create-form button[type='submit']").click();
  await expect(page.locator("#notice")).toContainText("请求与资源当前状态冲突");
  await expect(page.locator("#create-drawer")).toBeVisible();
  await page.locator("#close-create").click();
  await page.getByTestId("locale-en").click();

  const rename = page.locator("#document-actions [data-action='rename']");
  await expect(rename).toHaveCount(1);
  await rename.click();
  await expect(page.locator("#rename-form")).toBeVisible();
  const renamedTitle = `${created.title} renamed`;
  await page.locator("#rename-form [name='title']").fill(renamedTitle);
  await page.locator("#rename-form button[type='submit']").click();
  await expect(page.locator("#selected-title")).toHaveText(renamedTitle);

  const addVersion = page.locator("#document-actions [data-action='version']");
  await expect(addVersion).toHaveCount(1);
  await addVersion.click();
  await expect(page.locator("#version-form")).toBeVisible();
  await page.locator("#version-form [name='versionNumber']").fill("1.1");
  await page.locator("#version-form [name='file']").setInputFiles({
    name: "frontend-v1.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("FileWeft browser acceptance version 1.1.", "utf8"),
  });
  await page.locator("#version-form button[type='submit']").click();
  await expect(page.locator("#version-list")).toContainText("1.1");

  const downloads = page.locator("#version-list [data-version-download]");
  await expect(downloads).toHaveCount(2);
  const downloadPromise = page.waitForEvent("download");
  await downloads.nth(1).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("frontend-v1.txt");

  const contractsFolder = page.locator("[data-folder-id='contracts']");
  await expect(contractsFolder).toHaveCount(1);
  await contractsFolder.click();
  await expect(page.locator("#catalog-folder-title")).toHaveText("Contracts");
  const move = page.locator("#document-actions [data-action='moveFolder']");
  await expect(move).toHaveCount(1);
  await move.click();
  await expect(page.locator(`[data-document-id='${created.documentId}']`)).toHaveCount(1);
});

test("holds a dual-control document until both reviewer and administrator approve, then exposes its downstream mirror", async ({ browser, page }) => {
  await login(page, "editor@alpha");
  const created = await createFixture(page, "handbook");
  const submitDualControl = page.locator("#document-actions [data-action='submitDualControl']");
  await expect(submitDualControl).toHaveCount(1);
  await submitDualControl.click();
  await expect(page.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");

  const reviewerContext = await browser.newContext();
  try {
    const reviewerPage = await reviewerContext.newPage();
    await login(reviewerPage, "reviewer@alpha");
    await reviewerPage.getByTestId("workflow-inbox-nav").click();
    const reviewerTask = reviewerPage.locator(`[data-workflow-document='${created.documentId}']`);
    await expect(reviewerTask).toHaveCount(1);
    await reviewerTask.getByTestId("workflow-task-approve").click();
    await reviewerPage.locator("[data-panel='documents']").click();
    await selectDocument(reviewerPage, created.documentId);
    await expect(reviewerPage.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");
  } finally {
    await reviewerContext.close();
  }

  const administratorContext = await browser.newContext();
  try {
    const administratorPage = await administratorContext.newPage();
    await login(administratorPage, "admin@alpha");
    await administratorPage.getByTestId("workflow-inbox-nav").click();
    const administratorTask = administratorPage.locator(`[data-workflow-document='${created.documentId}']`);
    await expect(administratorTask).toHaveCount(1);
    await administratorTask.getByTestId("workflow-task-approve").click();
    await administratorPage.locator("[data-panel='documents']").click();
    await selectDocument(administratorPage, created.documentId);
    await expect(administratorPage.locator("#selected-state")).not.toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");
    await administratorPage.locator("[data-panel='platform']").click();
    await expect(administratorPage.locator("#platform-output")).toContainText("targetId");
    await administratorPage.locator("#process-outbox").click();
    await expect(administratorPage.locator("#notice")).toContainText("Outbox:");
  } finally {
    await administratorContext.close();
  }
});

test("returns a rejected document to an editor-controlled draft", async ({ browser, page }) => {
  await login(page, "editor@alpha");
  const created = await createFixture(page, "inventory");
  await page.locator("#document-actions [data-action='submit']").click();
  await expect(page.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");

  const reviewerContext = await browser.newContext();
  try {
    const reviewerPage = await reviewerContext.newPage();
    await login(reviewerPage, "reviewer@alpha");
    await selectDocument(reviewerPage, created.documentId);
    await reviewerPage.locator("#document-actions [data-action='reject']").click();
    await expect(reviewerPage.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "REJECTED");
  } finally {
    await reviewerContext.close();
  }

  const editorContext = await browser.newContext();
  try {
    const editorPage = await editorContext.newPage();
    await login(editorPage, "editor@alpha");
    await selectDocument(editorPage, created.documentId);
    await editorPage.locator("#document-actions [data-action='revise']").click();
    await expect(editorPage.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "DRAFT");
  } finally {
    await editorContext.close();
  }
});

test("runs resumable upload and maintenance through the administrator UI", async ({ page }) => {
  await login(page, "admin@alpha");
  await page.locator("[data-panel='uploads']").click();
  await expect(page.locator("#uploads-panel")).toBeVisible();
  await page.locator("#resumable-file").setInputFiles({
    name: "frontend-resumable.bin",
    mimeType: "application/octet-stream",
    buffer: Buffer.alloc((5 * 1024 * 1024) + 1, 0x46),
  });
  await page.locator("#resumable-upload-form button[type='submit']").click();
  await expect(page.locator("#resumable-upload-status")).toContainText("Upload completed as asset");

  await expect(page.locator("#resumable-maintenance")).toBeVisible();
  await page.locator("#resumable-maintenance").click();
  await expect(page.locator("#resumable-maintenance-output")).toBeVisible();
});
