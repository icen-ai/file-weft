import { expect, test } from "@playwright/test";

const ALPHA_INITIAL = "ALPHA-INITIAL-SECRET";
const ALPHA_DELAYED = "ALPHA-DELAYED-SECRET";
const BETA_CURRENT = "BETA-CURRENT";
const DELIVERY_READ = "document:delivery:read";

function deferred() {
  let resolve;
  const promise = new Promise((complete) => { resolve = complete; });
  return { promise, resolve };
}

function identity(username, permissions) {
  const tenantId = username.endsWith("@beta") ? "beta" : "alpha";
  return {
    token: `token-${tenantId}`,
    userId: `${tenantId}-viewer`,
    username,
    displayName: `${tenantId.toUpperCase()} Viewer`,
    tenantId,
    role: "VIEWER",
    permissions,
  };
}

function documentSummary(tenantId, marker) {
  return {
    id: `${tenantId}-document`,
    documentNumber: marker,
    title: `${marker} title`,
    lifecycleState: "PUBLISHED",
    currentVersionId: `${tenantId}-version`,
    folderId: "inbox",
    createdTime: 1_700_000_000_000,
    updatedTime: 1_700_000_000_000,
  };
}

function documentDetail(tenantId) {
  return {
    document: documentSummary(tenantId, tenantId === "alpha" ? ALPHA_INITIAL : BETA_CURRENT),
    versions: [],
    workflows: [],
    audits: [],
    operationLogs: [],
    deliveries: [{
      id: `${tenantId}-delivery`,
      profileId: "proof",
      targetId: "proof-target",
      displayName: "Proof target",
      connectorId: "proof-connector",
      requirement: "REQUIRED",
      ownerRef: null,
      status: "SUCCEEDED",
      externalId: `${tenantId}-external`,
      errorMessage: null,
      retryCount: 0,
      removalStatus: "NOT_REQUESTED",
      removalErrorMessage: null,
      removalRetryCount: 0,
      deliveryGeneration: 1,
      updatedTime: 1_700_000_000_000,
    }],
    tasks: [],
    agentResults: [],
    syncRecords: [],
    outboxEvents: [],
  };
}

async function fulfillJson(route, body, status = 200) {
  try {
    await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  } catch (error) {
    // A superseded browser request is expected to disappear while its mocked response is delayed.
    if (!/aborted|canceled|cancelled|closed/i.test(String(error))) throw error;
  }
}

async function installMockBackend(page, options = {}) {
  const alphaDocumentStarted = deferred();
  const releaseAlphaDocument = deferred();
  const alphaLoginStarted = deferred();
  const releaseAlphaLogin = deferred();
  const documentCalls = { alpha: 0, beta: 0 };
  let platformMirrorCalls = 0;

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === "/api/auth/login") {
      const { username } = request.postDataJSON();
      if (options.delayAlphaLogin && username === "viewer@alpha") {
        alphaLoginStarted.resolve();
        await releaseAlphaLogin.promise;
      }
      const permissions = ["document:read", ...(options.deliveryReadUsers?.has(username) ? [DELIVERY_READ] : [])];
      await fulfillJson(route, identity(username, permissions));
      return;
    }
    if (path === "/api/auth/logout") {
      try { await route.fulfill({ status: 204 }); } catch { /* superseded best-effort logout */ }
      return;
    }

    const authorization = request.headers().authorization || "";
    const tenantId = authorization.includes("token-beta") ? "beta" : "alpha";
    if (path === "/api/documents") {
      documentCalls[tenantId] += 1;
      let marker = tenantId === "alpha" ? ALPHA_INITIAL : BETA_CURRENT;
      if (tenantId === "alpha" && options.delayAlphaDocumentRefresh && documentCalls.alpha > 1) {
        marker = ALPHA_DELAYED;
        alphaDocumentStarted.resolve();
        await releaseAlphaDocument.promise;
      }
      await fulfillJson(route, [documentSummary(tenantId, marker)]);
      return;
    }
    if (path === "/api/catalog/folders") {
      await fulfillJson(route, [
        { id: "root", parentFolderId: null, displayName: `${tenantId} root` },
        { id: "inbox", parentFolderId: "root", displayName: `${tenantId} inbox` },
      ]);
      return;
    }
    if (path === "/api/delivery-profiles") {
      await fulfillJson(route, []);
      return;
    }
    if (path === `/api/documents/${tenantId}-document`) {
      await fulfillJson(route, documentDetail(tenantId));
      return;
    }
    if (path.endsWith("/platform-mirror")) {
      platformMirrorCalls += 1;
      await fulfillJson(route, [{ targetId: "proof-target", deliveryStatus: "SUCCEEDED", platform: { fileName: `${tenantId}.txt` } }]);
      return;
    }
    await fulfillJson(route, { code: "NOT_FOUND", message: `Unexpected mock API path: ${path}` }, 404);
  });

  await page.route("**/fileweft/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path.endsWith("/workflows")
      ? { items: [], nextCursor: null, total: 0 }
      : { deliveryTargets: [] };
    await fulfillJson(route, { code: "OK", message: "OK", data, traceId: "session-isolation" });
  });

  return {
    alphaDocumentStarted: alphaDocumentStarted.promise,
    releaseAlphaDocument: releaseAlphaDocument.resolve,
    alphaLoginStarted: alphaLoginStarted.promise,
    releaseAlphaLogin: releaseAlphaLogin.resolve,
    platformMirrorCalls: () => platformMirrorCalls,
  };
}

async function login(page, username) {
  await page.locator("#username").fill(username);
  await page.locator("#password").fill("dev-viewer");
  await page.locator("#login-form button[type='submit']").click();
  await expect(page.locator("#app-view")).toBeVisible();
}

test("aborts a delayed Alpha refresh and never restores Alpha DOM after same-page Beta login", async ({ page }) => {
  const backend = await installMockBackend(page, { delayAlphaDocumentRefresh: true });
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.addInitScript(() => {
    const nativeFetch = globalThis.fetch.bind(globalThis);
    globalThis.fetch = (resource, options = {}) => {
      const path = new URL(typeof resource === "string" ? resource : resource.url, location.href).pathname;
      if (path !== "/api/documents") return nativeFetch(resource, options);
      // Proves the generation fence as well as cancellation by emulating a transport that ignores AbortSignal.
      const { signal: _ignoredSignal, ...abortInsensitiveOptions } = options;
      return nativeFetch(resource, abortInsensitiveOptions);
    };
  });
  await page.goto("/");
  await login(page, "viewer@alpha");
  await expect(page.locator("#document-list")).toContainText(ALPHA_INITIAL);

  await page.locator("#refresh").click();
  await backend.alphaDocumentStarted;
  try {
    await page.locator("#logout").click();
    await expect(page.locator("#login-view")).toBeVisible();
    await expect(page.locator("#app-view")).toBeHidden();
    await expect(page.locator("#document-list")).toHaveText("");
    await expect(page.locator("#catalog-tree")).toHaveText("");
    await expect(page.locator("#identity-meta")).toHaveText("—");
    await expect(page.locator("#metric-tenant")).toHaveText("—");

    await page.evaluate((secrets) => {
      window.__fileweftSessionLeak = false;
      window.__fileweftSessionObserver = new MutationObserver(() => {
        if (secrets.some((secret) => document.body.textContent.includes(secret))) window.__fileweftSessionLeak = true;
      });
      window.__fileweftSessionObserver.observe(document.body, { childList: true, characterData: true, subtree: true });
    }, [ALPHA_INITIAL, ALPHA_DELAYED]);

    await login(page, "viewer@beta");
    await expect(page.locator("#identity-meta")).toHaveText("beta / viewer@beta");
    await expect(page.locator("#document-list")).toContainText(BETA_CURRENT);
    backend.releaseAlphaDocument();
    await page.waitForTimeout(150);

    expect(await page.evaluate(() => window.__fileweftSessionLeak)).toBe(false);
    await expect(page.locator("#app-view")).not.toContainText(ALPHA_INITIAL);
    await expect(page.locator("#app-view")).not.toContainText(ALPHA_DELAYED);
    await expect(page.locator("#document-list")).toContainText(BETA_CURRENT);
    expect(pageErrors).toEqual([]);
  } finally {
    backend.releaseAlphaDocument();
  }
});

test("keeps the last concurrent login attempt and its busy state authoritative", async ({ page }) => {
  const backend = await installMockBackend(page, { delayAlphaLogin: true });
  await page.goto("/");
  await page.locator("#username").fill("viewer@alpha");
  await page.locator("#password").fill("dev-viewer");
  await page.locator("#login-form").dispatchEvent("submit");
  await backend.alphaLoginStarted;

  await expect(page.locator("#login-form")).toHaveAttribute("aria-busy", "true");
  await expect(page.locator("#login-form button[type='submit']")).toBeDisabled();
  await page.locator("#username").fill("viewer@beta");
  await page.locator("#password").fill("dev-viewer");
  await page.locator("#login-form").dispatchEvent("submit");
  try {
    await expect(page.locator("#app-view")).toBeVisible();
    await expect(page.locator("#identity-meta")).toHaveText("beta / viewer@beta");
    await expect(page.locator("#document-list")).toContainText(BETA_CURRENT);
    backend.releaseAlphaLogin();
    await page.waitForTimeout(150);

    await expect(page.locator("#identity-meta")).toHaveText("beta / viewer@beta");
    await expect(page.locator("#app-view")).not.toContainText(ALPHA_INITIAL);
    await expect(page.locator("#login-form")).toHaveAttribute("aria-busy", "false");
    await expect(page.locator("#login-form button[type='submit']")).toBeEnabled();
  } finally {
    backend.releaseAlphaLogin();
  }
});

test("hides and never loads the downstream mirror without the server capability", async ({ page }) => {
  const backend = await installMockBackend(page, { deliveryReadUsers: new Set(["viewer@beta"]) });
  await page.goto("/");
  await login(page, "viewer@alpha");
  await expect(page.locator(".nav-item[data-panel='platform']")).toBeHidden();
  await page.locator("[data-document-id='alpha-document']").click();
  await expect(page.locator("#document-inspector")).toBeVisible();
  await page.locator(".nav-item[data-panel='platform']").dispatchEvent("click");
  await expect(page.locator("#documents-panel")).toBeVisible();
  expect(backend.platformMirrorCalls()).toBe(0);

  await page.locator("#logout").click();
  await login(page, "viewer@beta");
  await expect(page.locator(".nav-item[data-panel='platform']")).toBeVisible();
  await page.locator("[data-document-id='beta-document']").click();
  await expect.poll(backend.platformMirrorCalls).toBe(1);
});

test("clears tenant state when a browser restores the page from back-forward cache", async ({ page }) => {
  await installMockBackend(page);
  await page.goto("/");
  await login(page, "viewer@alpha");
  await expect(page.locator("#document-list")).toContainText(ALPHA_INITIAL);

  await page.evaluate(() => {
    window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
  });

  await expect(page.locator("#login-view")).toBeVisible();
  await expect(page.locator("#app-view")).toBeHidden();
  await expect(page.locator("#document-list")).toHaveText("");
  await expect(page.locator("#catalog-tree")).toHaveText("");
  await expect(page.locator("#identity-meta")).toHaveText("—");
  await expect(page.locator("body")).not.toContainText(ALPHA_INITIAL);
});
