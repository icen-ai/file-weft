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
  resumableBusy: false,
  stalledResumableCompletions: null,
  locale: localStorage.getItem("fileweft.locale") || DEFAULT_LOCALE,
};
const RESUMABLE_CHECKPOINT_PREFIX = "fileweft.resumable.v1";
const MINIMUM_RESUMABLE_CHUNK_BYTES = 5 * 1024 * 1024;
const MAXIMUM_RESUMABLE_PARTS = 10_000;
const V1_DOCUMENTS_PATH = "/fileweft/v1/documents";
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
  if (payload?.code === "DOCUMENT_NUMBER_CONFLICT") return t("error.documentNumberConflict");
  const key = payload?.code ? `error.v1.${payload.code}` : null;
  const translated = key ? t(key) : null;
  return translated && translated !== key ? translated : (payload?.message || `Request failed (${status})`);
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

const v1Api = async (path, options = {}) => {
  const envelope = await api(path, options);
  if (envelope?.code !== "OK") throw new Error(t("error.v1.invalidResponse"));
  return envelope.data;
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
    renderResumableUpload();
    renderStalledResumableCompletions();
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
  if (!can("file:upload:maintenance")) $("#resumable-maintenance-output").classList.add("hidden");
}

async function login(username, password) {
  const result = await api("/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }) });
  state.token = result.token;
  state.user = result;
  state.permissions = new Set(result.permissions || []);
  state.stalledResumableCompletions = null;
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
  renderResumableUpload();
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
    if (state.detail) renderActions();
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
    <button class="document-row ${document.id === state.selectedId ? "selected" : ""}" type="button" data-testid="document-row" data-document-id="${escapeHtml(document.id)}" data-lifecycle-state="${escapeHtml(document.lifecycleState)}">
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
  $("#selected-state").dataset.lifecycleState = document.lifecycleState;
  renderDeliveryProfile(document);
  $("#version-list").innerHTML = detail.versions.map((version) => {
    const download = can("document:download")
      ? `<button class="evidence-download" type="button" data-version-download="${escapeHtml(version.id)}" data-file-name="${escapeHtml(version.fileName)}">${escapeHtml(t("action.download"))}</button>` : "";
    return `<div class="evidence-item"><b>${escapeHtml(version.versionNumber)}${download}</b><small>${escapeHtml(version.fileName)} · ${escapeHtml(version.contentLength)} bytes · ${escapeHtml(version.contentHash || "—")}</small></div>`;
  }).join("") || emptyEvidence("empty.versions");
  $("#version-list").querySelectorAll("[data-version-download]").forEach((button) => button.addEventListener("click", () => {
    downloadDocument(button.dataset.versionDownload, button.dataset.fileName);
  }));
  $("#workflow-list").innerHTML = detail.workflows.map((workflow) => {
    const tasks = workflow.tasks.map((task) => `${escapeHtml(task.assigneeId || t("workflow.unassigned"))} · ${escapeHtml(localizedState(task.state))}${task.comment ? ` · ${escapeHtml(task.comment)}` : ""}`).join("<br />");
    return evidenceItem(`${localized("workflow.type", workflow.type)} / ${localizedState(workflow.state)}`, tasks);
  }).join("") || emptyEvidence("empty.workflow");
  $("#delivery-list").innerHTML = detail.deliveries.map((delivery) => {
    const responsibility = delivery.ownerRef ? ` · ${escapeHtml(delivery.ownerRef)}` : "";
    const error = delivery.errorMessage ? `<small class="delivery-error">${escapeHtml(delivery.errorMessage)}</small>` : "";
    const retry = delivery.retryCount ? ` · ${escapeHtml(interpolate("delivery.retries", { count: delivery.retryCount }))}` : "";
    const removal = delivery.removalStatus && delivery.removalStatus !== "NOT_REQUESTED"
      ? `<small>${escapeHtml(t("delivery.removal"))}: ${escapeHtml(localized("delivery.removal.status", delivery.removalStatus))}${delivery.removalRetryCount ? ` · ${escapeHtml(interpolate("delivery.retries", { count: delivery.removalRetryCount }))}` : ""}${delivery.removalErrorMessage ? ` · ${escapeHtml(delivery.removalErrorMessage)}` : ""}</small>` : "";
    const canRetry = (delivery.status === "FAILED" || delivery.removalStatus === "FAILED") && can("document:delivery:retry");
    const manualRetry = canRetry
      ? `<button class="delivery-retry" type="button" data-delivery-retry="${escapeHtml(delivery.id)}">${escapeHtml(t(delivery.removalStatus === "FAILED" ? "action.retryRemoval" : "action.retryDelivery"))}</button>` : "";
    return `<article class="delivery-card ${escapeHtml(delivery.status)}"><div><span class="delivery-requirement">${escapeHtml(localized("delivery.requirement", delivery.requirement))}</span><b>${escapeHtml(delivery.displayName)}</b><small>${escapeHtml(t("delivery.generation"))} ${escapeHtml(delivery.deliveryGeneration)} · ${escapeHtml(delivery.connectorId)}${responsibility}</small></div><div class="delivery-status"><strong>${escapeHtml(localized("delivery.status", delivery.status))}</strong><small>${escapeHtml(delivery.externalId || "—")}${retry}</small>${error}${removal}${manualRetry}</div></article>`;
  }).join("") || emptyEvidence("empty.delivery");
  $("#delivery-list").querySelectorAll("[data-delivery-retry]").forEach((button) => button.addEventListener("click", () => retryDelivery(button.dataset.deliveryRetry)));
  $("#task-list").innerHTML = detail.tasks.map((task) => evidenceItem(
    `${localizedTaskStatus(task.status)} / ${task.type}`,
    `${formatTime(task.createdTime)}${task.retryCount ? ` · ${escapeHtml(interpolate("task.retries", { count: task.retryCount }))}` : ""}${task.lastError ? ` · ${escapeHtml(task.lastError)}` : ""}`,
  )).join("") || emptyEvidence("empty.tasks");
  $("#agent-results-section").classList.toggle("hidden", !can("agent:suggestion:read"));
  $("#agent-result-list").innerHTML = detail.agentResults.map((result) => {
    const payload = safeJson(result.result);
    const suggestions = (payload.suggestions || []).map((suggestion) => {
      const confirmed = result.confirmations.some((confirmation) => confirmation.suggestionId === suggestion.id);
      const button = !confirmed && can("agent:suggestion:confirm")
        ? `<button class="delivery-retry" type="button" data-agent-confirm="${escapeHtml(result.taskId)}" data-agent-suggestion="${escapeHtml(suggestion.id)}">${escapeHtml(t("action.confirmAgent"))}</button>` : "";
      return `<small>${escapeHtml(suggestion.type)} · ${escapeHtml(JSON.stringify(suggestion.payload || {}))}${confirmed ? ` · ${escapeHtml(t("agent.confirmed"))}` : ""}${button}</small>`;
    }).join("<br />");
    return evidenceItem(`${localized("agent.capability", result.capability)} / ${escapeHtml(result.status)}`, `${escapeHtml(result.sourceEventType)} · ${suggestions}`);
  }).join("") || emptyEvidence("empty.agent");
  $("#agent-result-list").querySelectorAll("[data-agent-confirm]").forEach((button) => button.addEventListener("click", async () => {
    await api(`/api/documents/agent-results/${button.dataset.agentConfirm}/suggestions/${button.dataset.agentSuggestion}/confirm`, { method: "POST" });
    notice(t("notice.agentConfirmed"));
    await selectDocument(state.selectedId, false);
  }));
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
  $("#operation-log-list").innerHTML = detail.operationLogs.map((operation) => {
    const actorName = operation.operatorName || (operation.operatorId ? t("actor.unnamed") : t("actor.system"));
    const actorId = operation.operatorId ? ` · ${escapeHtml(operation.operatorId)}` : "";
    const trace = operation.traceId ? ` · ${escapeHtml(t("operation.trace"))}:${escapeHtml(operation.traceId)}` : ` · ${escapeHtml(t("operation.noTrace"))}`;
    const details = operation.details ? ` · ${escapeHtml(operation.details)}` : "";
    return evidenceItem(localizedAudit(operation.action), `${escapeHtml(actorName)}${actorId} · ${escapeHtml(formatTime(operation.createdTime))}${trace}${details}`);
  }).join("") || emptyEvidence("empty.operation");
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
  if (can("document:edit") && state.selectedFolderId && document.folderId !== state.selectedFolderId) {
    actions.push(actionButton("action.moveToFolder", "moveFolder"));
  }
  if (["DRAFT", "REJECTED"].includes(document.lifecycleState) && can("document:version:add")) actions.push(actionButton("action.addVersion", "version"));
  if (document.lifecycleState === "DRAFT" && can("document:submit")) {
    actions.push(actionButton("action.submit", "submit"), actionButton("action.submitDualControl", "submitDualControl"));
  }
  const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
  const task = workflow?.tasks.find((item) => item.state === "PENDING" && (!item.assigneeId || item.assigneeId === state.user.userId));
  if (document.lifecycleState === "PENDING_REVIEW" && task && can("document:audit")) {
    actions.push(actionButton("action.approve", "approve"), actionButton("action.reject", "reject"));
  }
  if (document.lifecycleState === "REJECTED" && can("document:revise")) actions.push(actionButton("action.revise", "revise"));
  if (document.lifecycleState === "PUBLISHED" && can("document:offline")) actions.push(actionButton("action.offline", "offline"));
  if (document.lifecycleState === "OFFLINE" && can("document:restore")) actions.push(actionButton("action.restore", "restore"));
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
    if (action === "moveFolder") {
      await api(`/api/documents/${id}/folder`, json({ folderId: state.selectedFolderId }));
      notice(t("notice.folderMoved"));
      await refreshDocuments();
      return;
    }
    if (["submit", "submitDualControl"].includes(action)) {
      const dualControl = action === "submitDualControl";
      await v1Api(`${V1_DOCUMENTS_PATH}/${id}/submit`, lifecycleJson({
        reviewRouteId: dualControl ? "dual-control" : "single-reviewer",
      }, dualControl ? "submit-dual-control" : "submit"));
      notice(t(dualControl ? "notice.submittedDualControl" : "notice.submitted"));
    }
    if (["approve", "reject"].includes(action)) {
      const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
      const task = workflow?.tasks.find((item) => item.state === "PENDING" && (!item.assigneeId || item.assigneeId === state.user.userId));
      if (!workflow || !task) throw new Error("No pending review task is available.");
      const body = { comment: action === "approve" ? "Development proof approved" : "Development proof requires revision" };
      if (action === "approve") body.deliveryProfileId = $("#delivery-profile").value || null;
      await v1Api(`/fileweft/v1/workflows/${workflow.id}/tasks/${task.id}/${action}`, lifecycleJson(body, action));
      notice(t(action === "approve" ? "notice.approved" : "notice.rejected"));
    }
    if (["revise", "restore", "offline", "archive"].includes(action)) {
      await v1Api(`${V1_DOCUMENTS_PATH}/${id}/${action}`, lifecycleRequest(action));
    }
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

async function downloadDocument(versionId, fileName) {
  try {
    const headers = new Headers();
    if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
    const response = await fetch(`${V1_DOCUMENTS_PATH}/${state.selectedId}/versions/${versionId}/content`, { headers });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(localizedApiError(text ? safeJson(text) : null, response.status));
    }
    const objectUrl = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = fileName || "fileweft-download";
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
    notice(t("notice.download"));
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
      ${canCreate ? `<button type="button" data-testid="fixture-upload-${fixture.id}" data-fixture-id="${fixture.id}">${escapeHtml(t("fixture.upload"))}</button>` : `<span class="fixture-locked">${escapeHtml(t("fixture.locked"))}</span>`}</div>
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
    const result = await v1Api(V1_DOCUMENTS_PATH, { method: "POST", body: form });
    state.selectedId = result.documentId;
    await refreshDocuments();
    if (state.detail?.document.id !== result.documentId) await selectDocument(result.documentId, false);
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
    const records = await api(`/api/documents/${state.selectedId}/platform-mirror`);
    $("#platform-output").textContent = JSON.stringify(records, null, 2);
  } catch (error) {
    $("#platform-output").textContent = error.message;
  }
}

function resumableCheckpointKey() {
  return `${RESUMABLE_CHECKPOINT_PREFIX}.${state.user?.tenantId || "anonymous"}`;
}

function readResumableCheckpoint() {
  try {
    const checkpoint = JSON.parse(localStorage.getItem(resumableCheckpointKey()) || "null");
    if (!checkpoint || checkpoint.version !== 1 || typeof checkpoint.idempotencyKey !== "string" ||
      typeof checkpoint.fileName !== "string" || !Number.isSafeInteger(checkpoint.contentLength) || checkpoint.contentLength <= 0 ||
      !Number.isSafeInteger(checkpoint.chunkSizeBytes) || checkpoint.chunkSizeBytes < MINIMUM_RESUMABLE_CHUNK_BYTES) return null;
    return checkpoint;
  } catch {
    return null;
  }
}

function writeResumableCheckpoint(checkpoint) {
  localStorage.setItem(resumableCheckpointKey(), JSON.stringify(checkpoint));
}

function clearResumableCheckpoint() {
  localStorage.removeItem(resumableCheckpointKey());
}

function newResumableIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `browser-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function setResumableStatus(message, type = "") {
  const element = $("#resumable-upload-status");
  element.className = `resumable-status ${type}`.trim();
  element.textContent = message;
}

function setResumableBusy(busy) {
  state.resumableBusy = busy;
  const submit = $("#resumable-upload-form button[type=submit]");
  if (submit) submit.disabled = busy;
  const abort = $("#resumable-abort");
  if (abort) abort.disabled = busy;
}

function renderResumableUpload() {
  if (!state.user || !can("file:upload")) return;
  const checkpoint = readResumableCheckpoint();
  const abort = $("#resumable-abort");
  if (!checkpoint) {
    setResumableStatus(t("upload.idle"));
    abort.classList.add("hidden");
    return;
  }
  const id = checkpoint.sessionId || t("upload.initializing");
  setResumableStatus(interpolate("upload.resume", {
    id,
    count: checkpoint.confirmedParts || 0,
    time: checkpoint.expiresAt ? formatTime(checkpoint.expiresAt) : "—",
  }), "active");
  abort.classList.toggle("hidden", !checkpoint.sessionId);
  abort.disabled = state.resumableBusy;
}

function checkpointMatchesFile(checkpoint, file) {
  return checkpoint.fileName === file.name && checkpoint.contentLength === file.size;
}

function chunkSizeFromForm() {
  const requestedMiB = Number($("#resumable-chunk-size").value);
  if (!Number.isInteger(requestedMiB) || requestedMiB < 5 || requestedMiB > 512) {
    throw new Error(t("upload.invalidChunk"));
  }
  return requestedMiB * 1024 * 1024;
}

async function startOrResumeUpload(event) {
  event.preventDefault();
  if (!can("file:upload") || state.resumableBusy) return;
  const file = $("#resumable-file").files[0];
  if (!file || file.size <= 0) {
    notice(t("upload.emptyFile"), "error");
    return;
  }
  let failureMessage = null;
  try {
    const chunkSizeBytes = chunkSizeFromForm();
    if (Math.ceil(file.size / chunkSizeBytes) > MAXIMUM_RESUMABLE_PARTS) throw new Error(t("upload.tooManyParts"));
    let checkpoint = readResumableCheckpoint();
    if (checkpoint && !checkpointMatchesFile(checkpoint, file)) throw new Error(t("upload.fileMismatch"));
    if (checkpoint && checkpoint.chunkSizeBytes !== chunkSizeBytes) throw new Error(t("upload.chunkMismatch"));
    if (!checkpoint) {
      checkpoint = {
        version: 1,
        idempotencyKey: newResumableIdempotencyKey(),
        fileName: file.name,
        contentLength: file.size,
        contentType: file.type || null,
        chunkSizeBytes,
        sessionId: null,
        expiresAt: null,
        confirmedParts: 0,
      };
      // Persists the idempotency key before the first request so a lost response can be recovered safely.
      writeResumableCheckpoint(checkpoint);
    }
    setResumableBusy(true);
    let session = checkpoint.sessionId ? await api(`/api/resumable-uploads/${checkpoint.sessionId}`) : null;
    if (session?.status === "COMPLETED") {
      clearResumableCheckpoint();
      $("#resumable-abort").classList.add("hidden");
      setResumableStatus(t("upload.alreadyCompleted"), "complete");
      return;
    }
    if (session && session.status !== "ACTIVE") throw new Error(interpolate("upload.unavailable", { status: session.status }));
    if (!session) {
      setResumableStatus(t("upload.initializing"), "active");
      session = await api("/api/resumable-uploads", json({
        fileName: checkpoint.fileName,
        contentLength: checkpoint.contentLength,
        assetType: "DOCUMENT",
        idempotencyKey: checkpoint.idempotencyKey,
        contentType: checkpoint.contentType,
      }));
      checkpoint.sessionId = session.id;
      checkpoint.expiresAt = session.expiresAt;
      checkpoint.confirmedParts = session.parts.length;
      writeResumableCheckpoint(checkpoint);
    }
    await uploadResumableParts(file, checkpoint, session);
  } catch (error) {
    failureMessage = error.message;
    setResumableStatus(error.message, "active");
    notice(error.message, "error");
  } finally {
    setResumableBusy(false);
    if (readResumableCheckpoint()) renderResumableUpload();
    if (failureMessage) setResumableStatus(failureMessage, "active");
  }
}

async function uploadResumableParts(file, checkpoint, session) {
  const totalParts = Math.ceil(file.size / checkpoint.chunkSizeBytes);
  const acknowledged = new Map(session.parts.map((part) => [part.partNumber, part]));
  for (let partNumber = 1; partNumber <= totalParts; partNumber += 1) {
    const start = (partNumber - 1) * checkpoint.chunkSizeBytes;
    const part = file.slice(start, Math.min(start + checkpoint.chunkSizeBytes, file.size));
    const known = acknowledged.get(partNumber);
    if (!known || known.contentLength !== part.size) {
      setResumableStatus(interpolate("upload.progress", {
        current: partNumber,
        total: totalParts,
        percent: Math.floor((start / file.size) * 100),
      }), "active");
      const accepted = await api(`/api/resumable-uploads/${checkpoint.sessionId}/parts/${partNumber}`, {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream", "X-FileWeft-Part-Length": String(part.size) },
        body: part,
      });
      if (accepted.partNumber !== partNumber || accepted.contentLength !== part.size) {
        throw new Error(t("upload.partRejected"));
      }
      acknowledged.set(partNumber, accepted);
      checkpoint.confirmedParts = acknowledged.size;
      writeResumableCheckpoint(checkpoint);
    }
  }
  setResumableStatus(interpolate("upload.progress", { current: totalParts, total: totalParts, percent: 100 }), "active");
  const completed = await api(`/api/resumable-uploads/${checkpoint.sessionId}/complete`, { method: "POST" });
  clearResumableCheckpoint();
  $("#resumable-file").value = "";
  $("#resumable-abort").classList.add("hidden");
  setResumableStatus(interpolate("upload.completed", { assetId: completed.fileAssetId }), "complete");
  notice(interpolate("upload.completed", { assetId: completed.fileAssetId }));
}

async function abortResumableUpload() {
  if (state.resumableBusy) return;
  const checkpoint = readResumableCheckpoint();
  if (!checkpoint) return;
  let failureMessage = null;
  try {
    setResumableBusy(true);
    if (checkpoint.sessionId) await api(`/api/resumable-uploads/${checkpoint.sessionId}`, { method: "DELETE" });
    clearResumableCheckpoint();
    $("#resumable-abort").classList.add("hidden");
    setResumableStatus(t("upload.aborted"));
    notice(t("upload.aborted"));
  } catch (error) {
    failureMessage = error.message;
    setResumableStatus(error.message, "active");
    notice(error.message, "error");
  } finally {
    setResumableBusy(false);
    if (readResumableCheckpoint()) renderResumableUpload();
    if (failureMessage) setResumableStatus(failureMessage, "active");
  }
}

function renderStalledResumableCompletions() {
  const output = $("#resumable-maintenance-output");
  if (!state.user || !can("file:upload:maintenance") || state.stalledResumableCompletions === null) {
    output.classList.add("hidden");
    return;
  }
  output.replaceChildren();
  output.classList.remove("hidden");
  const title = document.createElement("b");
  title.textContent = t("upload.maintenanceTitle");
  output.append(title);
  if (!state.stalledResumableCompletions.length) {
    const empty = document.createElement("small");
    empty.textContent = t("upload.maintenanceEmpty");
    output.append(empty);
    return;
  }
  state.stalledResumableCompletions.forEach((session) => {
    const item = document.createElement("small");
    const error = session.lastError ? ` · ${session.lastError}` : "";
    item.textContent = interpolate("upload.maintenanceItem", {
      id: session.id,
      name: session.fileName,
      time: formatTime(session.expiresAt),
    }) + error;
    output.append(item);
  });
}

async function loadStalledResumableCompletions() {
  if (!can("file:upload:maintenance")) return;
  try {
    state.stalledResumableCompletions = await api("/api/resumable-uploads/maintenance?limit=20");
    renderStalledResumableCompletions();
  } catch (error) {
    notice(error.message, "error");
  }
}

function activatePanel(panel) {
  const target = panel === "doctor" && !can("document:doctor") ? "documents" : panel;
  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.panel === target));
  ["documents", "fixtures", "platform", "doctor", "uploads"].forEach((name) => $(`#${name}-panel`).classList.toggle("hidden", name !== target));
  if (target === "platform") loadPlatform();
  if (target === "fixtures") renderFixtures();
  if (target === "uploads") renderResumableUpload();
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
const lifecycleKey = (action) => `dev-ui-${action}-${crypto.randomUUID()}`;
const lifecycleRequest = (action) => ({ method: "POST", headers: { "Idempotency-Key": lifecycleKey(action) } });
const lifecycleJson = (body, action) => ({
  ...json(body),
  headers: { "Content-Type": "application/json", "Idempotency-Key": lifecycleKey(action) },
});

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
  const form = event.currentTarget;
  try {
    const result = await v1Api(V1_DOCUMENTS_PATH, { method: "POST", body: new FormData(form) });
    $("#create-drawer").classList.add("hidden");
    form.reset();
    state.selectedId = result.documentId;
    await refreshDocuments();
    if (state.detail?.document.id !== result.documentId) await selectDocument(result.documentId, false);
  } catch (error) { notice(error.message, "error"); }
});
$("#rename-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    await v1Api(`${V1_DOCUMENTS_PATH}/${state.selectedId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: new FormData(form).get("title") }) });
    form.reset();
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
});
$("#version-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    await v1Api(`${V1_DOCUMENTS_PATH}/${state.selectedId}/versions`, { method: "POST", body: new FormData(form) });
    form.reset();
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
});
$("#resumable-upload-form").addEventListener("submit", startOrResumeUpload);
$("#resumable-abort").addEventListener("click", abortResumableUpload);
$("#resumable-maintenance").addEventListener("click", loadStalledResumableCompletions);
$("#logout").addEventListener("click", async () => {
  try { await api("/api/auth/logout", { method: "POST" }); } finally {
    state.token = null; state.user = null; state.permissions = new Set(); state.documents = []; state.folders = []; state.deliveryProfiles = []; state.selectedFolderId = null; state.selectedId = null; state.detail = null; state.resumableBusy = false; state.stalledResumableCompletions = null;
    $("#app-view").classList.add("hidden"); $("#login-view").classList.remove("hidden"); $("#document-inspector").classList.add("hidden"); $("#empty-inspector").classList.remove("hidden");
  }
});
document.querySelectorAll(".nav-item").forEach((button) => button.addEventListener("click", () => activatePanel(button.dataset.panel)));

applyTranslations();
