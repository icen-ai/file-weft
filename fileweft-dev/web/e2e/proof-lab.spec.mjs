import { expect, test } from "@playwright/test";

const presetId = (username) => `login-preset-${username.replace("@", "-")}`;
const uniqueDocumentNumber = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;

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
    const reviewerRow = reviewerPage.locator(`[data-document-id='${created.documentId}']`);
    await expect(reviewerRow).toHaveCount(1);
    await reviewerRow.click();
    const approve = reviewerPage.locator("#document-actions [data-action='approve']");
    await expect(approve).toHaveCount(1);
    await approve.click();
    await expect(reviewerPage.locator("#selected-state")).not.toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");
  } finally {
    await reviewerContext.close();
  }

  const administratorContext = await browser.newContext();
  try {
    const administratorPage = await administratorContext.newPage();
    await login(administratorPage, "admin@alpha");
    await expect(administratorPage.locator("#process-outbox")).toBeVisible();
    await expect(administratorPage.locator("#process-tasks")).toBeVisible();
    await expect(administratorPage.locator("[data-panel='doctor']")).toBeVisible();
    await expect(administratorPage.locator("[data-panel='uploads']")).toBeVisible();
  } finally {
    await administratorContext.close();
  }
});

test("creates, renames, versions, downloads, and moves a document through the editor UI", async ({ page }) => {
  await login(page, "editor@alpha");
  const created = await createDocument(page, "UI-EDIT");

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
    await selectDocument(reviewerPage, created.documentId);
    await reviewerPage.locator("#document-actions [data-action='approve']").click();
    await expect(reviewerPage.locator("#selected-state")).toHaveAttribute("data-lifecycle-state", "PENDING_REVIEW");
  } finally {
    await reviewerContext.close();
  }

  const administratorContext = await browser.newContext();
  try {
    const administratorPage = await administratorContext.newPage();
    await login(administratorPage, "admin@alpha");
    await selectDocument(administratorPage, created.documentId);
    await administratorPage.locator("#document-actions [data-action='approve']").click();
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

test("runs Doctor, durable task inspection, resumable upload, and maintenance through the administrator UI", async ({ page }) => {
  await login(page, "admin@alpha");
  await createFixture(page, "incident");
  const runDoctor = page.locator("#document-actions [data-action='doctor']");
  await expect(runDoctor).toHaveCount(1);
  await runDoctor.click();
  await expect(page.locator("#doctor-panel")).toBeVisible();
  await expect(page.locator("#doctor-output")).not.toContainText("Select a document");

  const scheduleDoctor = page.locator("#document-actions [data-action='scheduleDoctor']");
  await expect(scheduleDoctor).toHaveCount(1);
  await scheduleDoctor.click();
  await expect(page.locator("#notice")).toContainText("Doctor task queued");
  await page.locator("#process-tasks").click();
  await expect(page.locator("#notice")).toContainText("Tasks:");

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
