import { expect, test } from "@playwright/test";

const CHECKPOINT_PREFIX = "fileweft.resumable.v1";
const TENANT_ID = "alpha/tenant.with:separators";
const USER_A = "alpha/uploader.a:stable";
const USER_B = "alpha/uploader.b:stable";
const SESSION_ID = "session-owned-by-uploader-a";

function deferred() {
  let resolve;
  const promise = new Promise((complete) => { resolve = complete; });
  return { promise, resolve };
}

async function fulfillJson(route, body, status = 200) {
  try {
    await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  } catch {
    // Logout intentionally aborts uploader A's held part request.
  }
}

async function installMockBackend(page) {
  const partStarted = deferred();
  const releasePart = deferred();
  const resumableCalls = [];

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const authorization = request.headers().authorization || "";

    if (path === "/api/auth/login") {
      const { username } = request.postDataJSON();
      const uploaderA = username === "uploader-a@alpha";
      await fulfillJson(route, {
        token: uploaderA ? "token-uploader-a" : "token-uploader-b",
        userId: uploaderA ? USER_A : USER_B,
        username,
        displayName: uploaderA ? "Uploader A" : "Uploader B",
        tenantId: TENANT_ID,
        role: "EDITOR",
        permissions: ["document:read", "file:upload"],
      });
      return;
    }
    if (path === "/api/auth/logout") {
      try { await route.fulfill({ status: 204 }); } catch { /* superseded best-effort logout */ }
      return;
    }
    if (path === "/api/documents" || path === "/api/catalog/folders" || path === "/api/delivery-profiles") {
      await fulfillJson(route, []);
      return;
    }
    if (path.startsWith("/api/resumable-uploads")) {
      resumableCalls.push({ authorization, method: request.method(), path });
      if (path === "/api/resumable-uploads" && request.method() === "POST") {
        await fulfillJson(route, {
          id: SESSION_ID,
          fileName: "uploader-a.bin",
          contentLength: 5 * 1024 * 1024 + 1,
          status: "ACTIVE",
          expiresAt: 1_900_000_000_000,
          parts: [],
        });
        return;
      }
      if (path === `/api/resumable-uploads/${SESSION_ID}/parts/1` && request.method() === "PUT") {
        partStarted.resolve();
        await releasePart.promise;
        await fulfillJson(route, { partNumber: 1, contentLength: 5 * 1024 * 1024, eTag: "part-one" });
        return;
      }
      await fulfillJson(route, { code: "UNEXPECTED_RESUMABLE_CALL", message: `${request.method()} ${path}` }, 409);
      return;
    }
    await fulfillJson(route, { code: "NOT_FOUND", message: `Unexpected mock API path: ${path}` }, 404);
  });

  return {
    partStarted: partStarted.promise,
    releasePart: releasePart.resolve,
    callsFor(token) {
      return resumableCalls.filter((call) => call.authorization === `Bearer ${token}`);
    },
  };
}

async function login(page, username) {
  await page.locator("#username").fill(username);
  await page.locator("#password").fill("dev-uploader");
  await page.locator("#login-form button[type='submit']").click();
  await expect(page.locator("#app-view")).toBeVisible();
}

test("scopes resumable checkpoints to tenant and stable user without cross-user requests", async ({ page }) => {
  const backend = await installMockBackend(page);
  await page.addInitScript((prefix) => {
    window.__fileweftCheckpointReads = [];
    const nativeGetItem = Storage.prototype.getItem;
    Storage.prototype.getItem = function getItem(key) {
      if (String(key).startsWith(prefix)) window.__fileweftCheckpointReads.push(String(key));
      return nativeGetItem.call(this, key);
    };
  }, CHECKPOINT_PREFIX);
  await page.goto("/");

  const legacyKey = `${CHECKPOINT_PREFIX}.${TENANT_ID}`;
  await page.evaluate(({ key, prefix }) => {
    localStorage.setItem(key, JSON.stringify({ version: 1, sessionId: "legacy-tenant-session" }));
    window.__fileweftCheckpointReads = [];
    window.__fileweftCheckpointPrefix = prefix;
  }, { key: legacyKey, prefix: CHECKPOINT_PREFIX });

  await login(page, "uploader-a@alpha");
  expect(await page.evaluate((key) => localStorage.getItem(key), legacyKey)).toBeNull();
  await page.locator("[data-panel='uploads']").click();
  await page.locator("#resumable-file").setInputFiles({
    name: "uploader-a.bin",
    mimeType: "application/octet-stream",
    buffer: Buffer.alloc(5 * 1024 * 1024 + 1, 0x41),
  });
  await page.locator("#resumable-upload-form button[type='submit']").click();
  await backend.partStarted;

  const checkpoint = await page.evaluate((prefix) => {
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
      .filter((key) => key?.startsWith(`${prefix}.tenant.`));
    return { keys, value: keys.length === 1 ? JSON.parse(localStorage.getItem(keys[0])) : null };
  }, CHECKPOINT_PREFIX);
  expect(checkpoint.keys).toHaveLength(1);
  const uploaderAKey = checkpoint.keys[0];
  expect(uploaderAKey).toMatch(/^fileweft\.resumable\.v1\.tenant\.[A-Za-z0-9_-]+\.user\.[A-Za-z0-9_-]+$/);
  expect(uploaderAKey).not.toContain(TENANT_ID);
  expect(uploaderAKey).not.toContain(USER_A);
  expect(checkpoint.value).toMatchObject({ version: 1, sessionId: SESSION_ID, fileName: "uploader-a.bin" });

  await page.locator("#logout").click();
  await expect(page.locator("#login-view")).toBeVisible();
  backend.releasePart();
  await page.evaluate(() => { window.__fileweftCheckpointReads = []; });
  await login(page, "uploader-b@alpha");
  await page.locator("[data-panel='uploads']").click();
  await expect(page.locator("#resumable-upload-status")).not.toContainText(SESSION_ID);
  await expect(page.locator("#resumable-abort")).toBeHidden();

  const uploaderBReads = await page.evaluate(() => window.__fileweftCheckpointReads.slice());
  expect(uploaderBReads.length).toBeGreaterThan(0);
  expect(uploaderBReads).not.toContain(uploaderAKey);
  expect(new Set(uploaderBReads).size).toBe(1);
  expect(backend.callsFor("token-uploader-b")).toEqual([]);

  await page.locator("#resumable-abort").dispatchEvent("click");
  await page.locator("#resumable-upload-form").dispatchEvent("submit");
  await page.waitForTimeout(50);
  expect(backend.callsFor("token-uploader-b")).toEqual([]);
  expect(await page.evaluate((key) => localStorage.getItem(key), uploaderAKey)).not.toBeNull();

  await page.locator("#logout").click();
  await page.evaluate(() => { window.__fileweftCheckpointReads = []; });
  const uploaderACallCount = backend.callsFor("token-uploader-a").length;
  await login(page, "uploader-a@alpha");
  await page.locator("[data-panel='uploads']").click();
  await expect(page.locator("#resumable-upload-status")).toContainText(SESSION_ID);
  await expect(page.locator("#resumable-abort")).toBeVisible();
  expect(await page.evaluate(() => window.__fileweftCheckpointReads.slice())).toContain(uploaderAKey);
  expect(backend.callsFor("token-uploader-a")).toHaveLength(uploaderACallCount);
});
