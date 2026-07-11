import { expect, test } from "@playwright/test";

const presetId = (username) => `login-preset-${username.replace("@", "-")}`;

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
