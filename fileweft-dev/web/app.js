import { DEFAULT_LOCALE, translate } from "./i18n.js";

const FIXTURES = [
  { id: "contract", path: "./fixtures/supply-contract.txt", fileName: "supply-contract.txt", contentType: "text/plain" },
  { id: "handbook", path: "./fixtures/operations-handbook.md", fileName: "operations-handbook.md", contentType: "text/markdown" },
  { id: "inventory", path: "./fixtures/inventory-register.csv", fileName: "inventory-register.csv", contentType: "text/csv" },
  { id: "incident", path: "./fixtures/incident-report.json", fileName: "incident-report.json", contentType: "application/json" },
];

const state = {
  token: null,
  user: null,
  permissions: new Set(),
  documents: [],
  folders: [],
  deliveryProfiles: [],
  selectedFolderId: null,
  selectedId: null,
  detail: null,
  locale: localStorage.getItem("fileweft.locale") || DEFAULT_LOCALE,
};
const $ = (selector) => document.querySelector(selector);
const t = (key) => translate(state.locale, key);
const can = (action) => state.permissions.has(action);
const escapeHtml = (value = "") => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
const safeJson = (text) => { try { return JSON.parse(text); } catch { return text; } };
const localized = (prefix, value) => {
  const key = `${prefix}.${value}`;
  const label = t(key);
  return label === key ? value : label;
};
const localizedState = (value) => localized("state", value);
const localizedAudit = (value) => localized("audit", value);
const localizedTaskStatus = (value) => localized("task.status", value);
const formatTime = (value) => value ? new Date(Number(value)).toLocaleString(state.locale === "zh" ? "zh-CN" : "en-GB", { hour12: false }) : "—";

const localizedApiError = (payload, status) => {
  const key = payload?.code === "DOCUMENT_NUMBER_CONFLICT" ? "error.documentNumberConflict" : null;
  return key ? t(key) : (payload?.message || `Request failed (${status})`);
};

const api = async (path, options = {}) => {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  const payload = text ? safeJson(text) : null;
  if (!response.ok) throw new Error(localizedApiError(payload, response.status));
  return payload;
};

function interpolate(key, values) {
  return Object.entries(values).reduce((message, [name, value]) => message.replace(`{${name}}`, String(value)), t(key));
}

function notice(message, type = "") {
  const element = $("#notice");
  element.textContent = message;
  element.className = `notice ${type}`;
  window.clearTimeout(notice.timer);
  notice.timer = window.setTimeout(() => element.classList.add("hidden"), 5000);
}

function applyTranslations() {
  document.documentElement.lang = state.locale === "zh" ? "zh-CN" : "en";
  document.title = t("document.title");
  document.querySelectorAll("[data-i18n]").forEach((element) => { element.textContent = t(element.dataset.i18n); });
  document.querySelectorAll("[data-i18n-html]").forEach((element) => { element.innerHTML = t(element.dataset.i18nHtml); });
  document.querySelectorAll("[data-i18n-placeholder]").forEach((element) => { element.placeholder = t(element.dataset.i18nPlaceholder); });
  document.querySelectorAll("[data-i18n-aria]").forEach((element) => { element.setAttribute("aria-label", t(element.dataset.i18nAria)); });
  document.querySelectorAll("[data-locale]").forEach((element) => { element.setAttribute("aria-pressed", String(element.dataset.locale === state.locale)); });
  if (state.user) {
    $("#identity-role").textContent = t(`role.${state.user.role}`);
    renderCatalog();
    renderDocuments();
    syncFolderOptions();
    renderFixtures();
    if (state.detail) renderInspector();
  }
}

function setLocale(locale) {
  state.locale = locale;
  localStorage.setItem("fileweft.locale", locale);
  applyTranslations();
}

function syncPermissionSurface() {
  document.querySelectorAll("[data-permission]").forEach((element) => {
    element.classList.toggle("hidden", !can(element.dataset.permission));
  });
}

async function login(username, password) {
  const result = await api("/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }) });
  state.token = result.token;
  state.user = result;
  state.permissions = new Set(result.permissions || []);
  $("#login-view").classList.add("hidden");
  $("#app-view").classList.remove("hidden");
  $("#identity-name").textContent = result.displayName;
  $("#identity-meta").textContent = `${result.tenantId} / ${result.username}`;
  $("#identity-role").textContent = t(`role.${result.role}`);
  $("#metric-tenant").textContent = result.tenantId;
  $("#catalog-tenant-mark").textContent = result.tenantId.toUpperCase();
  syncPermissionSurface();
  await Promise.all([refreshDocuments(), loadDeliveryProfiles()]);
  if (state.detail) renderInspector();
  renderFixtures();
  notice(t("notice.login"));
}

async function refreshDocuments() {
  const [documents, folders] = await Promise.all([
    api("/api/documents?limit=60"),
    api("/api/catalog/folders"),
  ]);
  state.documents = documents;
  state.folders = folders;
  ensureSelectedFolder();
  renderCatalog();
  renderDocuments();
  syncFolderOptions();
  updateMetrics();
  if (state.selectedId && state.documents.some((document) => document.id === state.selectedId)) await selectDocument(state.selectedId, false);
}

async function loadDeliveryProfiles() {
  state.deliveryProfiles = await api("/api/delivery-profiles");
}

function updateMetrics() {
  $("#metric-documents").textContent = state.documents.length;
  $("#metric-review").textContent = state.documents.filter((document) => document.lifecycleState === "PENDING_REVIEW").length;
  $("#metric-sync").textContent = state.documents.filter((document) => document.lifecycleState === "SYNC_ERROR").length;
}

function folderLabel(folder) {
  const key = `catalog.${state.user?.tenantId}.${folder.id}`;
  const translated = t(key);
  return translated === key ? folder.displayName : translated;
}

function folderById(folderId) {
  return state.folders.find((folder) => folder.id === folderId);
}

function ensureSelectedFolder() {
  if (state.folders.some((folder) => folder.id === state.selectedFolderId)) return;
  state.selectedFolderId = state.folders.find((folder) => folder.id === "inbox")?.id || state.folders[0]?.id || null;
}

function documentsInSelectedFolder() {
  if (state.selectedFolderId === "root") return state.documents;
  return state.documents.filter((document) => document.folderId === state.selectedFolderId);
}

function renderCatalog() {
  const tree = $("#catalog-tree");
  if (!tree || !state.user) return;
  if (!state.folders.length) {
    tree.innerHTML = `<div class="tree-empty">${escapeHtml(t("catalog.empty"))}</div>`;
    return;
  }
  const children = (parentFolderId) => state.folders.filter((folder) => folder.parentFolderId === parentFolderId);
  const renderBranch = (folder, level) => {
    const directCount = state.documents.filter((document) => document.folderId === folder.id).length;
    return `<button class="tree-node ${folder.id === state.selectedFolderId ? "selected" : ""}" type="button" role="treeitem" aria-level="${level}" aria-selected="${folder.id === state.selectedFolderId}" data-folder-id="${escapeHtml(folder.id)}" style="--tree-level:${level}">
      <span class="tree-branch">${children(folder.id).length ? "⌄" : "·"}</span><span class="tree-folder">${escapeHtml(folderLabel(folder))}</span><small>${directCount}</small>
    </button>${children(folder.id).map((child) => renderBranch(child, level + 1)).join("")}`;
  };
  tree.innerHTML = children(null).map((folder) => renderBranch(folder, 1)).join("");
  tree.querySelectorAll("[data-folder-id]").forEach((button) => button.addEventListener("click", () => {
    state.selectedFolderId = button.dataset.folderId;
    renderCatalog();
    renderDocuments();
    syncFolderOptions();
  }));
}

function syncFolderOptions() {
  const field = $("#folder-id");
  if (!field || !state.user) return;
  const selectable = state.folders.filter((folder) => folder.parentFolderId !== null);
  field.innerHTML = selectable.map((folder) => `<option value="${escapeHtml(folder.id)}">${escapeHtml(folderLabel(folder))}</option>`).join("");
  if (selectable.some((folder) => folder.id === state.selectedFolderId)) field.value = state.selectedFolderId;
}

function renderDocuments() {
  const list = $("#document-list");
  const folder = folderById(state.selectedFolderId);
  const documents = documentsInSelectedFolder();
  $("#catalog-folder-title").textContent = folder ? folderLabel(folder) : t("catalog.empty");
  $("#catalog-folder-meta").textContent = interpolate("catalog.documentCount", { count: documents.length });
  if (!documents.length) {
    list.innerHTML = `<div class="catalog-empty"><span>∅</span><b>${escapeHtml(t("empty.documents.title"))}</b><small>${escapeHtml(t("catalog.emptyFolder"))}</small></div>`;
    return;
  }
  list.innerHTML = documents.map((document) => `
    <button class="document-row ${document.id === state.selectedId ? "selected" : ""}" type="button" data-document-id="${escapeHtml(document.id)}">
      <span><b>${escapeHtml(document.documentNumber)}</b><small>${escapeHtml(document.title)}</small></span>
      <span class="state-tag ${document.lifecycleState}">${escapeHtml(localizedState(document.lifecycleState))}</span>
      <span><small>${escapeHtml(formatTime(document.updatedTime))}</small></span>
      <span><small>${escapeHtml(document.currentVersionId || "—")}</small></span>
    </button>`).join("");
  list.querySelectorAll("[data-document-id]").forEach((button) => button.addEventListener("click", () => selectDocument(button.dataset.documentId)));
}

async function selectDocument(documentId, refreshPanels = true) {
  state.selectedId = documentId;
  state.detail = await api(`/api/documents/${documentId}`);
  state.selectedFolderId = state.detail.document.folderId || state.selectedFolderId;
  $("#empty-inspector").classList.add("hidden");
  $("#document-inspector").classList.remove("hidden");
  $("#selection-count").textContent = t("inspector.title");
  renderCatalog();
  renderDocuments();
  syncFolderOptions();
  renderInspector();
  if (refreshPanels) loadPlatform();
}

function evidenceItem(title, content) {
  return `<div class="evidence-item"><b>${escapeHtml(title)}</b><small>${content}</small></div>`;
}

function emptyEvidence(key) {
  return `<div class="evidence-item"><small>${escapeHtml(t(key))}</small></div>`;
}

function renderInspector() {
  const detail = state.detail;
  const document = detail.document;
  $("#selected-number").textContent = document.documentNumber;
  $("#selected-title").textContent = document.title;
  $("#selected-state").textContent = localizedState(document.lifecycleState);
  $("#selected-state").className = `state-tag ${document.lifecycleState}`;
  renderDeliveryProfile(document);
  $("#version-list").innerHTML = detail.versions.map((version) => evidenceItem(
    version.versionNumber,
    `${escapeHtml(version.fileName)} · ${escapeHtml(version.contentLength)} bytes · ${escapeHtml(version.contentHash || "—")}`,
  )).join("") || emptyEvidence("empty.versions");
  $("#workflow-list").innerHTML = detail.workflows.map((workflow) => {
    const tasks = workflow.tasks.map((task) => `${escapeHtml(task.assigneeId || t("workflow.unassigned"))} · ${escapeHtml(localizedState(task.state))}${task.comment ? ` · ${escapeHtml(task.comment)}` : ""}`).join("<br />");
    return evidenceItem(`${localized("workflow.type", workflow.type)} / ${localizedState(workflow.state)}`, tasks);
  }).join("") || emptyEvidence("empty.workflow");
  $("#delivery-list").innerHTML = detail.deliveries.map((delivery) => {
    const responsibility = delivery.ownerRef ? ` · ${escapeHtml(delivery.ownerRef)}` : "";
    const error = delivery.errorMessage ? `<small class="delivery-error">${escapeHtml(delivery.errorMessage)}</small>` : "";
    const retry = delivery.retryCount ? ` · ${escapeHtml(interpolate("delivery.retries", { count: delivery.retryCount }))}` : "";
    const manualRetry = delivery.status === "FAILED" && can("document:delivery:retry")
      ? `<button class="delivery-retry" type="button" data-delivery-retry="${escapeHtml(delivery.id)}">${escapeHtml(t("action.retryDelivery"))}</button>` : "";
    return `<article class="delivery-card ${escapeHtml(delivery.status)}"><div><span class="delivery-requirement">${escapeHtml(localized("delivery.requirement", delivery.requirement))}</span><b>${escapeHtml(delivery.displayName)}</b><small>${escapeHtml(delivery.connectorId)}${responsibility}</small></div><div class="delivery-status"><strong>${escapeHtml(localized("delivery.status", delivery.status))}</strong><small>${escapeHtml(delivery.externalId || "—")}${retry}</small>${error}${manualRetry}</div></article>`;
  }).join("") || emptyEvidence("empty.delivery");
  $("#delivery-list").querySelectorAll("[data-delivery-retry]").forEach((button) => button.addEventListener("click", () => retryDelivery(button.dataset.deliveryRetry)));
  $("#task-list").innerHTML = detail.tasks.map((task) => evidenceItem(
    `${localizedTaskStatus(task.status)} / ${task.type}`,
    `${formatTime(task.createdTime)}${task.retryCount ? ` · ${escapeHtml(interpolate("task.retries", { count: task.retryCount }))}` : ""}${task.lastError ? ` · ${escapeHtml(task.lastError)}` : ""}`,
  )).join("") || emptyEvidence("empty.tasks");
  $("#doctor-record-list").innerHTML = detail.doctorRecords.map((record) => `
    <div class="evidence-item doctor-record"><b>${escapeHtml(record.status)} / ${escapeHtml(formatTime(record.createdTime))}</b>
      <small>${escapeHtml(record.taskId)}</small><button type="button" data-doctor-record="${escapeHtml(record.id)}">${escapeHtml(t("action.openDoctorRecord"))}</button></div>`
  ).join("") || emptyEvidence("empty.doctorHistory");
  $("#doctor-record-list").querySelectorAll("[data-doctor-record]").forEach((button) => button.addEventListener("click", () => {
    const record = detail.doctorRecords.find((item) => item.id === button.dataset.doctorRecord);
    if (!record) return;
    $("#doctor-output").textContent = JSON.stringify(safeJson(record.report), null, 2);
    activatePanel("doctor");
  }));
  $("#sync-list").innerHTML = detail.syncRecords.map((sync) => evidenceItem(
    `${localizedState(sync.status)} / ${sync.connectorName}`,
    `${escapeHtml(sync.externalId || "—")}${sync.errorMessage ? ` · ${escapeHtml(sync.errorMessage)}` : ""}`,
  )).join("") || emptyEvidence("empty.sync");
  $("#audit-list").innerHTML = detail.audits.map((audit) => {
    const actorName = audit.operatorName || (audit.operatorId ? t("actor.unnamed") : t("actor.system"));
    const actorId = audit.operatorId ? ` · ${escapeHtml(audit.operatorId)}` : "";
    return evidenceItem(localizedAudit(audit.action), `${escapeHtml(actorName)}${actorId} · ${escapeHtml(formatTime(audit.createdTime))}`);
  }).join("") || emptyEvidence("empty.audit");
  renderActions();
}

function renderDeliveryProfile(document) {
  const section = $("#delivery-profile-section");
  const selector = $("#delivery-profile");
  const mayChoose = state.deliveryProfiles.length && ["DRAFT", "PENDING_REVIEW"].includes(document.lifecycleState);
  section.classList.toggle("hidden", !mayChoose);
  if (!mayChoose) return;
  selector.innerHTML = state.deliveryProfiles.map((profile) => `<option value="${escapeHtml(profile.id)}">${escapeHtml(profile.displayName)} · ${profile.targets.map((target) => target.displayName).join(" / ")}</option>`).join("");
}

function actionButton(key, action) {
  return `<button type="button" data-action="${action}">${escapeHtml(t(key))}</button>`;
}

function renderActions() {
  const document = state.detail.document;
  const actions = [];
  if (can("document:doctor")) actions.push(actionButton("action.doctor", "doctor"), actionButton("action.scheduleDoctor", "scheduleDoctor"));
  if (can("document:rename")) actions.push(actionButton("action.rename", "rename"));
  if (["DRAFT", "REJECTED"].includes(document.lifecycleState) && can("document:version:add")) actions.push(actionButton("action.addVersion", "version"));
  if (document.lifecycleState === "DRAFT" && can("document:submit")) actions.push(actionButton("action.submit", "submit"));
  const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
  const task = workflow?.tasks.find((item) => item.state === "PENDING");
  const isAssignedReviewer = task && (!task.assigneeId || task.assigneeId === state.user.userId);
  if (document.lifecycleState === "PENDING_REVIEW" && isAssignedReviewer && can("document:audit")) {
    actions.push(actionButton("action.approve", "approve"), actionButton("action.reject", "reject"));
  }
  if (document.lifecycleState === "REJECTED" && can("document:revise")) actions.push(actionButton("action.revise", "revise"));
  if (document.lifecycleState === "PUBLISHED" && can("document:offline")) actions.push(actionButton("action.offline", "offline"));
  if (document.lifecycleState === "PUBLISHED" && can("document:archive")) actions.push(actionButton("action.archive", "archive"));
  $("#document-actions").innerHTML = actions.join("");
  $("#document-actions").querySelectorAll("[data-action]").forEach((button) => button.addEventListener("click", () => runAction(button.dataset.action)));
}

async function runAction(action) {
  const id = state.selectedId;
  try {
    if (action === "rename") return $("#rename-form").classList.toggle("hidden");
    if (action === "version") return $("#version-form").classList.toggle("hidden");
    if (action === "doctor") {
      const report = await api(`/api/documents/${id}/doctor`);
      $("#doctor-output").textContent = JSON.stringify(report, null, 2);
      activatePanel("doctor");
      return;
    }
    if (action === "scheduleDoctor") {
      await api(`/api/documents/${id}/doctor/tasks`, { method: "POST" });
      notice(t("notice.doctorScheduled"));
      await refreshDocuments();
      return;
    }
    if (action === "submit") {
      await api(`/api/documents/${id}/submit`, json({ reviewerId: `${state.user.tenantId}-reviewer` }));
      notice(t("notice.submitted"));
    }
    if (["approve", "reject"].includes(action)) {
      const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
      const task = workflow?.tasks.find((item) => item.state === "PENDING");
      if (!workflow || !task) throw new Error("No pending review task is available.");
      await api(`/api/documents/workflows/${workflow.id}/tasks/${task.id}/${action}`, json({
        comment: action === "approve" ? "Development proof approved" : "Development proof requires revision",
        deliveryProfileId: action === "approve" ? $("#delivery-profile").value || null : null,
      }));
      notice(t(action === "approve" ? "notice.approved" : "notice.rejected"));
    }
    if (["revise", "offline", "archive"].includes(action)) await api(`/api/documents/${id}/${action}`, { method: "POST" });
    await refreshDocuments();
  } catch (error) {
    notice(error.message, "error");
  }
}

async function retryDelivery(deliveryId) {
  try {
    await api(`/api/documents/delivery-targets/${deliveryId}/retry`, { method: "POST" });
    notice(t("notice.deliveryRetried"));
    await refreshDocuments();
    loadPlatform();
  } catch (error) {
    notice(error.message, "error");
  }
}

function renderFixtures() {
  if (!state.user) return;
  const route = routeFor(state.user.role);
  const permissions = [...state.permissions].filter((permission) => permission !== "document:read");
  $("#role-route").innerHTML = `
    <div class="route-index">${escapeHtml(t(`role.${state.user.role}`))}</div>
    <div><span class="eyebrow">${escapeHtml(t("route.permissions"))}</span><h3>${escapeHtml(t(route.title))}</h3><p>${escapeHtml(t(route.detail))}</p>
      <div class="permission-chips">${permissions.map((permission) => `<span>${escapeHtml(permission)}</span>`).join("") || `<span>document:read</span>`}</div>
      <button class="primary" type="button" data-role-route="${route.action}">${escapeHtml(t(route.button))}<span>→</span></button>
    </div>`;
  $("#role-route [data-role-route]").addEventListener("click", () => runRoleRoute(route.action));
  const canCreate = can("document:create");
  $("#fixture-grid").innerHTML = FIXTURES.map((fixture) => `
    <article class="fixture-card">
      <span class="fixture-type">${escapeHtml(fixture.fileName.split(".").pop().toUpperCase())}</span>
      <h3>${escapeHtml(t(`fixture.${fixture.id}.name`))}</h3>
      <p>${escapeHtml(t(`fixture.${fixture.id}.detail`))}</p>
      <div class="fixture-actions"><a href="${fixture.path}" download="${fixture.fileName}">${escapeHtml(t("fixture.download"))}</a>
      ${canCreate ? `<button type="button" data-fixture-id="${fixture.id}">${escapeHtml(t("fixture.upload"))}</button>` : `<span class="fixture-locked">${escapeHtml(t("fixture.locked"))}</span>`}</div>
    </article>`).join("");
  $("#fixture-grid").querySelectorAll("[data-fixture-id]").forEach((button) => button.addEventListener("click", () => createFixture(button.dataset.fixtureId)));
}

function routeFor(role) {
  return {
    ADMIN: { title: "route.ADMIN.title", detail: "route.ADMIN.detail", button: "route.ADMIN.button", action: "outbox" },
    EDITOR: { title: "route.EDITOR.title", detail: "route.EDITOR.detail", button: "route.EDITOR.button", action: "fixtures" },
    REVIEWER: { title: "route.REVIEWER.title", detail: "route.REVIEWER.detail", button: "route.REVIEWER.button", action: "review" },
    VIEWER: { title: "route.VIEWER.title", detail: "route.VIEWER.detail", button: "route.VIEWER.button", action: "readonly" },
  }[role];
}

async function runRoleRoute(action) {
  if (action === "fixtures") return activatePanel("fixtures");
  if (action === "outbox") return processOutbox();
  if (action === "readonly") {
    activatePanel("documents");
    notice(t("notice.readOnly"));
    return;
  }
  const pending = state.documents.find((document) => document.lifecycleState === "PENDING_REVIEW");
  if (!pending) {
    notice(t("notice.noPending"));
    return;
  }
  await selectDocument(pending.id);
  activatePanel("documents");
}

async function createFixture(fixtureId) {
  if (!can("document:create")) return;
  const fixture = FIXTURES.find((item) => item.id === fixtureId);
  try {
    const response = await fetch(fixture.path);
    if (!response.ok) throw new Error(`Fixture download failed (${response.status})`);
    const blob = await response.blob();
    const form = new FormData();
    const suffix = Date.now().toString(36).toUpperCase();
    form.append("documentNumber", `LAB-${fixture.id.toUpperCase()}-${suffix}`);
    form.append("title", t(`fixture.${fixture.id}.title`));
    form.append("folderId", state.selectedFolderId === "root" ? "inbox" : (state.selectedFolderId || "inbox"));
    form.append("file", new File([blob], fixture.fileName, { type: fixture.contentType }));
    const detail = await api("/api/documents", { method: "POST", body: form });
    state.selectedId = detail.document.id;
    await refreshDocuments();
    activatePanel("documents");
    notice(t("notice.fixtureCreated"));
  } catch (error) {
    notice(error.message, "error");
  }
}

async function loadPlatform() {
  if (!state.selectedId || !state.user) return;
  try {
    const deliveries = state.detail?.deliveries || [];
    if (!deliveries.length) {
      $("#platform-output").textContent = t("platform.empty");
      return;
    }
    const records = await Promise.all(deliveries.map(async (delivery) => {
      const response = await fetch(`/platform/v1/documents/${state.user.tenantId}/${state.selectedId}`, { headers: { "X-FileWeft-Target": delivery.targetId } });
      const text = await response.text();
      return { targetId: delivery.targetId, deliveryStatus: delivery.status, platform: response.ok ? safeJson(text) : null };
    }));
    $("#platform-output").textContent = JSON.stringify(records, null, 2);
  } catch (error) {
    $("#platform-output").textContent = error.message;
  }
}

function activatePanel(panel) {
  const target = panel === "doctor" && !can("document:doctor") ? "documents" : panel;
  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.panel === target));
  ["documents", "fixtures", "platform", "doctor"].forEach((name) => $(`#${name}-panel`).classList.toggle("hidden", name !== target));
  if (target === "platform") loadPlatform();
  if (target === "fixtures") renderFixtures();
}

async function processOutbox() {
  try {
    const result = await api("/api/outbox/process?limit=20", { method: "POST" });
    notice(interpolate("notice.outbox", result));
    await refreshDocuments();
    loadPlatform();
  } catch (error) {
    notice(error.message, "error");
  }
}

async function processTasks() {
  try {
    const result = await api("/api/tasks/process?limit=20", { method: "POST" });
    notice(interpolate("notice.tasks", result));
    await refreshDocuments();
  } catch (error) {
    notice(error.message, "error");
  }
}

const json = (body) => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try { await login(form.get("username"), form.get("password")); } catch (error) { notice(error.message, "error"); }
});
document.querySelectorAll(".preset").forEach((button) => button.addEventListener("click", () => { $("#username").value = button.dataset.user; $("#password").value = button.dataset.password; }));
document.querySelectorAll("[data-locale]").forEach((button) => button.addEventListener("click", () => setLocale(button.dataset.locale)));
$("#refresh").addEventListener("click", () => refreshDocuments().catch((error) => notice(error.message, "error")));
$("#process-outbox").addEventListener("click", processOutbox);
$("#process-tasks").addEventListener("click", processTasks);
$("#open-create").addEventListener("click", () => $("#create-drawer").classList.remove("hidden"));
$("#close-create").addEventListener("click", () => $("#create-drawer").classList.add("hidden"));
$("#create-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const detail = await api("/api/documents", { method: "POST", body: new FormData(event.currentTarget) });
    $("#create-drawer").classList.add("hidden");
    event.currentTarget.reset();
    state.selectedId = detail.document.id;
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
});
$("#rename-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await api(`/api/documents/${state.selectedId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: new FormData(event.currentTarget).get("title") }) });
    event.currentTarget.reset();
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
});
$("#version-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await api(`/api/documents/${state.selectedId}/versions`, { method: "POST", body: new FormData(event.currentTarget) });
    event.currentTarget.reset();
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
});
$("#logout").addEventListener("click", async () => {
  try { await api("/api/auth/logout", { method: "POST" }); } finally {
    state.token = null; state.user = null; state.permissions = new Set(); state.documents = []; state.folders = []; state.deliveryProfiles = []; state.selectedFolderId = null; state.selectedId = null; state.detail = null;
    $("#app-view").classList.add("hidden"); $("#login-view").classList.remove("hidden"); $("#document-inspector").classList.add("hidden"); $("#empty-inspector").classList.remove("hidden");
  }
});
document.querySelectorAll(".nav-item").forEach((button) => button.addEventListener("click", () => activatePanel(button.dataset.panel)));

applyTranslations();
