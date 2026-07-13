import { DEFAULT_LOCALE, translate } from "./i18n.js";

const FIXTURES = [
  { id: "contract", path: "./fixtures/supply-contract.txt", fileName: "supply-contract.txt", contentType: "text/plain" },
  { id: "handbook", path: "./fixtures/operations-handbook.md", fileName: "operations-handbook.md", contentType: "text/markdown" },
  { id: "inventory", path: "./fixtures/inventory-register.csv", fileName: "inventory-register.csv", contentType: "text/csv" },
  { id: "incident", path: "./fixtures/incident-report.json", fileName: "incident-report.json", contentType: "application/json" },
];

function newDoctorState(documentId = null) {
  return {
    documentId,
    immediateReport: null,
    immediateLoading: false,
    task: null,
    taskReport: null,
    taskLoading: false,
    taskRefreshFailure: false,
    scheduleKey: null,
    systemReport: null,
    systemLoading: false,
    contextGeneration: 0,
    pollTimer: null,
    pollGeneration: 0,
    pollFailures: 0,
  };
}

function newRuntimeSurfaceState() {
  return {
    health: null,
    plugins: [],
    loading: false,
    loaded: false,
    healthError: false,
    pluginsError: false,
  };
}

const state = {
  token: null,
  user: null,
  permissions: new Set(),
  documents: [],
  folders: [],
  deliveryProfiles: [],
  workflowTasks: [],
  workflowHistory: [],
  workflowDecisionEvidence: [],
  syncStatus: null,
  selectedFolderId: null,
  selectedId: null,
  detail: null,
  auditLogs: [],
  doctor: newDoctorState(),
  runtimeSurface: newRuntimeSurfaceState(),
  resumableBusy: false,
  stalledResumableCompletions: null,
  locale: localStorage.getItem("fileweft.locale") || DEFAULT_LOCALE,
};
const RESUMABLE_CHECKPOINT_PREFIX = "fileweft.resumable.v1";
const MINIMUM_RESUMABLE_CHUNK_BYTES = 5 * 1024 * 1024;
const MAXIMUM_RESUMABLE_PARTS = 10_000;
const V1_DOCUMENTS_PATH = "/fileweft/v1/documents";
const V1_WORKFLOW_TASKS_PATH = "/fileweft/v1/workflows/tasks";
const V1_SYSTEM_DOCTOR_PATH = "/fileweft/v1/doctor";
const V1_HEALTH_PATH = "/fileweft/v1/health";
const V1_PLUGINS_PATH = "/fileweft/v1/plugins";
const DOWNSTREAM_MIRROR_CAPABILITY = "document:delivery:read";
const DOCTOR_REPORT_STATUSES = new Set(["HEALTHY", "WARNING", "ERROR", "SKIPPED"]);
const DOCTOR_TASK_STATUSES = new Set(["PENDING", "RUNNING", "RETRY", "SUCCESS", "FAILED"]);
const DOCTOR_TASK_TERMINAL_STATUSES = new Set(["SUCCESS", "FAILED"]);
const DOCTOR_PUBLIC_CHECKERS = new Set([
  "permission", "lifecycle", "workflow", "storage", "catalog", "delivery-profile", "connector", "agent",
  "task", "extensions",
]);
let doctorPollSequence = 0;
let doctorContextSequence = 0;
let documentSelectionSequence = 0;
let sessionGeneration = 0;
const activeRequestControllers = new Set();
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
const localizedDoctorStatus = (value) => localized("doctor.status", value);
const formatTime = (value) => value ? new Date(Number(value)).toLocaleString(state.locale === "zh" ? "zh-CN" : "en-GB", { hour12: false }) : "—";

const localizedApiError = (payload, status) => {
  if (payload?.code === "DOCUMENT_NUMBER_CONFLICT") return t("error.documentNumberConflict");
  const key = payload?.code ? `error.v1.${payload.code}` : null;
  const translated = key ? t(key) : null;
  return translated && translated !== key ? translated : (payload?.message || `Request failed (${status})`);
};

function supersededSessionError() {
  const error = new Error("The browser session changed before the request completed.");
  error.name = "AbortError";
  error.sessionSuperseded = true;
  return error;
}

function assertCurrentSession(generation) {
  if (generation !== sessionGeneration) throw supersededSessionError();
}

function isRequestCancellation(error) {
  return error?.name === "AbortError" || error?.sessionSuperseded === true;
}

function reportRequestError(error) {
  if (!isRequestCancellation(error)) notice(error.message, "error");
}

async function fetchForCurrentSession(path, options, consume) {
  const generation = sessionGeneration;
  const controller = new AbortController();
  const { signal: externalSignal, ...requestOptions } = options || {};
  const abortFromExternalSignal = () => controller.abort();
  if (externalSignal?.aborted) controller.abort();
  else externalSignal?.addEventListener("abort", abortFromExternalSignal, { once: true });
  activeRequestControllers.add(controller);
  try {
    assertCurrentSession(generation);
    const response = await fetch(path, {
      ...requestOptions,
      cache: requestOptions.cache || "no-store",
      signal: controller.signal,
    });
    assertCurrentSession(generation);
    const result = await consume(response);
    assertCurrentSession(generation);
    return result;
  } catch (error) {
    if (generation !== sessionGeneration && !isRequestCancellation(error)) throw supersededSessionError();
    throw error;
  } finally {
    activeRequestControllers.delete(controller);
    externalSignal?.removeEventListener("abort", abortFromExternalSignal);
  }
}

const api = async (path, options = {}) => {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  const result = await fetchForCurrentSession(path, { ...options, headers }, async (response) => {
    const text = await response.text();
    return { ok: response.ok, status: response.status, payload: text ? safeJson(text) : null };
  });
  if (!result.ok) throw new Error(localizedApiError(result.payload, result.status));
  const payload = result.payload;
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
    renderWorkflowInbox();
    renderResumableUpload();
    renderStalledResumableCompletions();
    renderDoctorPanel();
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
  const platformNavigation = document.querySelector(".nav-item[data-panel='platform']");
  platformNavigation?.classList.toggle("hidden", !can(DOWNSTREAM_MIRROR_CAPABILITY));
  $("#audit-section")?.classList.toggle("hidden", !(can("document:audit") && can("document:read")));
  if (!can("file:upload:maintenance")) $("#resumable-maintenance-output").classList.add("hidden");
}

function setLoginBusy(busy) {
  const form = $("#login-form");
  form.setAttribute("aria-busy", String(busy));
  form.querySelector("button[type='submit']").disabled = busy;
}

function resetSessionState() {
  cancelDoctorPolling();
  documentSelectionSequence += 1;
  state.token = null;
  state.user = null;
  state.permissions = new Set();
  state.documents = [];
  state.folders = [];
  state.deliveryProfiles = [];
  state.workflowTasks = [];
  state.workflowHistory = [];
  state.workflowDecisionEvidence = [];
  state.syncStatus = null;
  state.selectedFolderId = null;
  state.selectedId = null;
  state.detail = null;
  state.auditLogs = [];
  state.doctor = newDoctorState();
  state.doctor.contextGeneration = ++doctorContextSequence;
  state.doctor.pollGeneration = ++doctorPollSequence;
  state.runtimeSurface = newRuntimeSurfaceState();
  state.resumableBusy = false;
  state.stalledResumableCompletions = null;
}

function clearSensitiveDom() {
  window.clearTimeout(notice.timer);
  $("#notice").textContent = "";
  $("#notice").className = "notice hidden";
  $("#app-view").classList.add("hidden");
  $("#login-view").classList.remove("hidden");
  $("#login-form").reset();

  $("#identity-name").textContent = "—";
  $("#identity-meta").textContent = "—";
  $("#identity-role").textContent = "—";
  $("#metric-tenant").textContent = "—";
  $("#metric-documents").textContent = "0";
  $("#metric-review").textContent = "0";
  $("#metric-sync").textContent = "0";
  $("#catalog-tenant-mark").textContent = "—";
  $("#catalog-folder-title").textContent = "—";
  $("#catalog-folder-meta").textContent = "—";

  [
    "#catalog-tree", "#document-list", "#workflow-task-list", "#role-route", "#fixture-grid", "#document-actions",
    "#version-list", "#workflow-list", "#delivery-list", "#task-list", "#agent-result-list", "#sync-list",
    "#audit-list", "#resumable-maintenance-output", "#runtime-health-output", "#plugin-inventory-list",
  ].forEach((selector) => $(selector).replaceChildren());
  $("#platform-output").textContent = t("platform.empty");

  $("#create-drawer").classList.add("hidden");
  $("#create-form").reset();
  $("#folder-id").replaceChildren();
  $("#selection-count").textContent = t("inspector.none");
  $("#empty-inspector").classList.remove("hidden");
  $("#document-inspector").classList.add("hidden");
  $("#selected-number").textContent = "—";
  $("#selected-title").textContent = "—";
  $("#selected-state").textContent = "—";
  $("#selected-state").className = "state-tag";
  delete $("#selected-state").dataset.lifecycleState;
  $("#delivery-profile-section").classList.add("hidden");
  $("#delivery-profile").replaceChildren();
  $("#rename-form").classList.add("hidden");
  $("#rename-form").reset();
  $("#version-form").classList.add("hidden");
  $("#version-form").reset();
  $("#agent-results-section").classList.add("hidden");

  $("#resumable-upload-form").reset();
  $("#resumable-upload-status").className = "resumable-status";
  $("#resumable-upload-status").textContent = t("upload.idle");
  $("#resumable-abort").classList.add("hidden");
  $("#resumable-abort").disabled = false;
  $("#resumable-maintenance-output").classList.add("hidden");

  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.panel === "documents"));
  ["documents", "workflow", "fixtures", "platform", "doctor", "uploads"].forEach((name) => {
    $("#" + name + "-panel").classList.toggle("hidden", name !== "documents");
  });
  syncPermissionSurface();
  renderDoctorPanel();
}

function invalidateSession() {
  sessionGeneration += 1;
  activeRequestControllers.forEach((controller) => controller.abort());
  activeRequestControllers.clear();
  resetSessionState();
  clearSensitiveDom();
  return sessionGeneration;
}

async function login(username, password) {
  const generation = invalidateSession();
  setLoginBusy(true);
  try {
    const result = await api("/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }) });
    assertCurrentSession(generation);
    state.token = result.token;
    state.user = result;
    state.permissions = new Set(result.permissions || []);
    removeLegacyTenantCheckpoint(result.tenantId);
    resetDoctorState();
    state.stalledResumableCompletions = null;
    syncPermissionSurface();
    await Promise.all([refreshDocuments(), loadDeliveryProfiles()]);
    assertCurrentSession(generation);
    $("#identity-name").textContent = result.displayName;
    $("#identity-meta").textContent = `${result.tenantId} / ${result.username}`;
    $("#identity-role").textContent = t(`role.${result.role}`);
    $("#metric-tenant").textContent = result.tenantId;
    $("#catalog-tenant-mark").textContent = result.tenantId.toUpperCase();
    if (state.detail) renderInspector();
    renderFixtures();
    renderResumableUpload();
    $("#login-view").classList.add("hidden");
    $("#app-view").classList.remove("hidden");
    notice(t("notice.login"));
  } catch (error) {
    if (generation !== sessionGeneration || isRequestCancellation(error)) return;
    invalidateSession();
    setLoginBusy(false);
    throw error;
  } finally {
    if (generation === sessionGeneration) setLoginBusy(false);
  }
}

async function refreshDocuments() {
  const [documents, folders, workflowPage] = await Promise.all([
    api("/api/documents?limit=60"),
    api("/api/catalog/folders"),
    can("document:audit") && can("document:read")
      ? v1Api(`${V1_WORKFLOW_TASKS_PATH}?limit=100`)
      : Promise.resolve({ items: [], nextCursor: null }),
  ]);
  state.documents = documents;
  state.folders = folders;
  state.workflowTasks = workflowPage?.items || [];
  ensureSelectedFolder();
  renderCatalog();
  renderDocuments();
  renderWorkflowInbox();
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

function pendingTaskForDocument(documentId) {
  return state.workflowTasks.find((item) => item.document.id === documentId && item.actionableByCurrentUser);
}

function renderWorkflowInbox() {
  const list = $("#workflow-task-list");
  if (!list) return;
  if (!state.user || !can("document:audit")) {
    list.replaceChildren();
    return;
  }
  if (!state.workflowTasks.length) {
    list.innerHTML = `<div class="workflow-task-empty"><span>✓</span><b>${escapeHtml(t("workflow.inbox.emptyTitle"))}</b><small>${escapeHtml(t("workflow.inbox.emptyDetail"))}</small></div>`;
    return;
  }
  list.innerHTML = state.workflowTasks.map((item) => `
    <article class="workflow-task-card" data-testid="workflow-task-card" data-workflow-document="${escapeHtml(item.document.id)}">
      <div><span class="eyebrow">${escapeHtml(item.document.documentNumber)} · ${escapeHtml(item.task.assignedToCurrentUser ? t("workflow.inbox.assigned") : t("workflow.unassigned"))}</span><h3>${escapeHtml(item.document.title)}</h3><small>${escapeHtml(localized("workflow.type", item.workflowType))} · ${escapeHtml(item.task.id)} · ${escapeHtml(formatTime(item.task.createdTime))}</small></div>
      <div class="workflow-task-actions"><button type="button" data-workflow-decision="approve" data-workflow-task="${escapeHtml(item.task.id)}" data-testid="workflow-task-approve">${escapeHtml(t("action.approve"))}</button><button type="button" data-workflow-decision="reject" data-workflow-task="${escapeHtml(item.task.id)}" data-testid="workflow-task-reject">${escapeHtml(t("action.reject"))}</button><button class="workflow-open" type="button" data-workflow-open="${escapeHtml(item.document.id)}">${escapeHtml(t("workflow.inbox.openDocument"))}</button></div>
    </article>`).join("");
  list.querySelectorAll("[data-workflow-open]").forEach((button) => button.addEventListener("click", async () => {
    await selectDocument(button.dataset.workflowOpen);
    activatePanel("documents");
  }));
  list.querySelectorAll("[data-workflow-decision]").forEach((button) => button.addEventListener("click", async () => {
    const item = state.workflowTasks.find((candidate) => candidate.task.id === button.dataset.workflowTask);
    if (!item) return;
    try {
      await decideWorkflowTask(item, button.dataset.workflowDecision, null);
      await refreshDocuments();
    } catch (error) {
      reportRequestError(error);
    }
  }));
}

async function decideWorkflowTask(item, action, deliveryProfileId) {
  const body = { comment: action === "approve" ? "Development proof approved" : "Development proof requires revision" };
  if (action === "approve" && deliveryProfileId) body.deliveryProfileId = deliveryProfileId;
  await v1Api(`/fileweft/v1/workflows/${item.task.workflowId}/tasks/${item.task.id}/${action}`, lifecycleJson(body, action));
  notice(t(action === "approve" ? "notice.approved" : "notice.rejected"));
}

async function selectDocument(documentId, refreshPanels = true) {
  const generation = sessionGeneration;
  const selectionSequence = ++documentSelectionSequence;
  if (state.doctor.documentId !== documentId) resetDoctorState(documentId);
  const encodedDocumentId = encodeURIComponent(documentId);
  const mayReadAudit = can("document:audit") && can("document:read");
  state.auditLogs = [];
  const [detail, workflowPage, decisionEvidencePage, syncStatus, auditPage] = await Promise.all([
    api(`/api/documents/${encodedDocumentId}`),
    v1Api(`${V1_DOCUMENTS_PATH}/${encodedDocumentId}/workflows?limit=100`),
    mayReadAudit
      ? v1Api(`${V1_DOCUMENTS_PATH}/${encodedDocumentId}/workflow-decisions?limit=100`)
      : Promise.resolve({ items: [], nextCursor: null }),
    v1Api(`${V1_DOCUMENTS_PATH}/${encodedDocumentId}/sync-status`),
    mayReadAudit
      ? v1Api(`${V1_DOCUMENTS_PATH}/${encodedDocumentId}/logs?limit=100`)
      : Promise.resolve({ items: [], nextCursor: null }),
  ]);
  if (generation !== sessionGeneration || selectionSequence !== documentSelectionSequence || state.doctor.documentId !== documentId) return;
  state.selectedId = documentId;
  state.detail = detail;
  state.workflowHistory = workflowPage?.items || [];
  state.workflowDecisionEvidence = decisionEvidencePage?.items || [];
  state.syncStatus = syncStatus;
  state.auditLogs = Array.isArray(auditPage?.items) ? auditPage.items : [];
  state.selectedFolderId = state.detail.document.folderId || state.selectedFolderId;
  $("#empty-inspector").classList.add("hidden");
  $("#document-inspector").classList.remove("hidden");
  $("#selection-count").textContent = t("inspector.title");
  renderCatalog();
  renderDocuments();
  syncFolderOptions();
  renderInspector();
  renderDoctorPanel();
  if (refreshPanels && can(DOWNSTREAM_MIRROR_CAPABILITY)) loadPlatform();
}

function evidenceItem(title, content) {
  return `<div class="evidence-item"><b>${escapeHtml(title)}</b><small>${content}</small></div>`;
}

function emptyEvidence(key) {
  return `<div class="evidence-item"><small>${escapeHtml(t(key))}</small></div>`;
}

function doctorRequestKey() {
  if (globalThis.crypto?.randomUUID) return `dev-ui-doctor-${globalThis.crypto.randomUUID()}`;
  return `dev-ui-doctor-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function documentDoctorPath(documentId) {
  return `${V1_DOCUMENTS_PATH}/${encodeURIComponent(documentId)}/doctor`;
}

function normalizedDoctorReportStatus(value) {
  return DOCTOR_REPORT_STATUSES.has(value) ? value : "ERROR";
}

function normalizedDoctorTaskStatus(value) {
  return DOCTOR_TASK_STATUSES.has(value) ? value : "FAILED";
}

function normalizedDoctorChecker(value) {
  return DOCTOR_PUBLIC_CHECKERS.has(value) ? value : "extensions";
}

function isDoctorReport(report, documentId = null) {
  if (!report || typeof report !== "object" || !DOCTOR_REPORT_STATUSES.has(report.status) || !Array.isArray(report.checks)) return false;
  if (documentId === null) return !("documentId" in report);
  return report.documentId === documentId;
}

function doctorCheckerLabel(value) {
  const checker = normalizedDoctorChecker(value);
  const key = `doctor.checker.${checker}`;
  const translated = t(key);
  return translated === key ? t("doctor.checker.extensions") : translated;
}

function setDoctorStatus(element, status, idle = false) {
  const normalized = idle ? "SKIPPED" : normalizedDoctorReportStatus(status);
  element.className = `doctor-status ${normalized}`;
  element.textContent = idle ? t("doctor.status.idle") : localizedDoctorStatus(normalized);
  element.dataset.doctorStatus = idle ? "IDLE" : normalized;
}

function setDoctorTaskStatus(element, status, idle = false) {
  const normalized = idle ? "SKIPPED" : normalizedDoctorTaskStatus(status);
  element.className = `doctor-status ${normalized}`;
  element.textContent = idle ? t("doctor.status.idle") : localizedDoctorStatus(normalized);
  element.dataset.doctorStatus = idle ? "IDLE" : normalized;
}

function doctorLoading(target, key) {
  target.innerHTML = `<div class="doctor-empty loading">${escapeHtml(t(key))}</div>`;
}

function doctorEmpty(target, key) {
  target.innerHTML = `<div class="doctor-empty">${escapeHtml(t(key))}</div>`;
}

function renderDoctorReport(target, report) {
  const checks = [];
  const checkerNames = new Set();
  (Array.isArray(report?.checks) ? report.checks : []).slice(0, 64).forEach((candidate) => {
    const checkerName = normalizedDoctorChecker(candidate?.checkerName);
    if (checkerNames.has(checkerName)) return;
    checkerNames.add(checkerName);
    checks.push({ checkerName, status: normalizedDoctorReportStatus(candidate?.status) });
  });
  const inspected = Number.isFinite(Number(report?.inspectedTime))
    ? `<div class="doctor-report-meta">${escapeHtml(interpolate("doctor.checkedAt", { time: formatTime(report.inspectedTime) }))}</div>`
    : "";
  const renderedChecks = checks.map((check) => {
    const checker = doctorCheckerLabel(check.checkerName);
    const reason = interpolate(`doctor.reason.${check.status}`, { checker });
    const repair = ["WARNING", "ERROR"].includes(check.status)
      ? `<p class="doctor-check-repair"><b>${escapeHtml(t("doctor.repair"))}</b><br />${escapeHtml(t("doctor.repair.default"))}</p>`
      : "";
    return `<article class="doctor-check" data-testid="doctor-check-${escapeHtml(check.checkerName)}" data-doctor-status="${escapeHtml(check.status)}">
      <strong class="doctor-check-name">${escapeHtml(checker)}</strong><span class="doctor-check-state">${escapeHtml(localizedDoctorStatus(check.status))}</span>
      <p class="doctor-check-reason">${escapeHtml(reason)}</p>${repair}</article>`;
  }).join("");
  target.innerHTML = inspected + (renderedChecks || `<div class="doctor-empty">${escapeHtml(t("doctor.async.noReport"))}</div>`);
}

function cancelDoctorPolling() {
  if (state.doctor.pollTimer !== null) window.clearTimeout(state.doctor.pollTimer);
  state.doctor.pollTimer = null;
  state.doctor.pollGeneration = ++doctorPollSequence;
}

function resetDoctorState(documentId = null) {
  if (state.doctor?.pollTimer !== null) window.clearTimeout(state.doctor.pollTimer);
  const next = newDoctorState(documentId);
  next.contextGeneration = ++doctorContextSequence;
  next.pollGeneration = ++doctorPollSequence;
  state.doctor = next;
  if ($("#doctor-output")) renderDoctorPanel();
}

function renderDoctorPanel() {
  const immediateOutput = $("#doctor-output");
  if (!immediateOutput) return;
  const selected = state.doctor.documentId === state.selectedId && state.detail?.document?.id === state.selectedId
    ? state.detail.document
    : null;
  const canInspectDocument = Boolean(selected && can("document:doctor"));
  const context = $("#doctor-document-context");
  context.textContent = selected
    ? interpolate("doctor.documentContext", { number: selected.documentNumber, title: selected.title })
    : t("doctor.noSelection");

  const run = $("#run-doctor");
  const schedule = $("#schedule-doctor");
  const runSystem = $("#run-system-doctor");
  const activeTask = Boolean(state.doctor.task) &&
    !DOCTOR_TASK_TERMINAL_STATUSES.has(normalizedDoctorTaskStatus(state.doctor.task.status));
  const retryablePoll = activeTask && state.doctor.taskRefreshFailure;
  run.disabled = !canInspectDocument || state.doctor.immediateLoading;
  schedule.disabled = !canInspectDocument || state.doctor.taskLoading || (activeTask && !retryablePoll);
  schedule.textContent = t(retryablePoll ? "action.retryDoctorPoll" : "action.scheduleDoctor");
  runSystem.disabled = !can("system:doctor:read") || state.doctor.systemLoading;

  if (state.doctor.immediateLoading) {
    setDoctorStatus($("#doctor-immediate-status"), "SKIPPED", true);
    doctorLoading(immediateOutput, "doctor.loading");
  } else if (state.doctor.immediateReport) {
    setDoctorStatus($("#doctor-immediate-status"), state.doctor.immediateReport.status);
    renderDoctorReport(immediateOutput, state.doctor.immediateReport);
  } else {
    setDoctorStatus($("#doctor-immediate-status"), "SKIPPED", true);
    doctorEmpty(immediateOutput, "doctor.empty");
  }

  const task = state.doctor.task;
  const taskSummary = $("#doctor-task-summary");
  if (task) {
    const time = task.updatedTime || task.createdTime;
    taskSummary.textContent = `${task.id} · ${localizedDoctorStatus(normalizedDoctorTaskStatus(task.status))}${time ? ` · ${formatTime(time)}` : ""}`;
    setDoctorTaskStatus($("#doctor-task-status"), task.status);
  } else {
    taskSummary.textContent = t("doctor.async.empty");
    setDoctorTaskStatus($("#doctor-task-status"), "SKIPPED", true);
  }
  const taskOutput = $("#doctor-task-output");
  if (state.doctor.taskReport) {
    renderDoctorReport(taskOutput, state.doctor.taskReport);
  } else if (state.doctor.taskRefreshFailure) {
    doctorEmpty(taskOutput, "doctor.pollFailed");
  } else if (state.doctor.taskLoading || (task && !DOCTOR_TASK_TERMINAL_STATUSES.has(normalizedDoctorTaskStatus(task.status)))) {
    doctorLoading(taskOutput, "doctor.polling");
  } else if (task) {
    doctorEmpty(taskOutput, "doctor.async.noReport");
  } else {
    doctorEmpty(taskOutput, "doctor.async.hint");
  }

  const systemOutput = $("#doctor-system-output");
  if (state.doctor.systemLoading) {
    setDoctorStatus($("#doctor-system-status"), "SKIPPED", true);
    doctorLoading(systemOutput, "doctor.loading");
  } else if (state.doctor.systemReport) {
    setDoctorStatus($("#doctor-system-status"), state.doctor.systemReport.status);
    renderDoctorReport(systemOutput, state.doctor.systemReport);
  } else {
    setDoctorStatus($("#doctor-system-status"), "SKIPPED", true);
    doctorEmpty(systemOutput, "doctor.system.empty");
  }
  renderRuntimeSurface();
}

function renderRuntimeSurface() {
  const status = $("#runtime-health-status");
  const healthOutput = $("#runtime-health-output");
  const pluginOutput = $("#plugin-inventory-list");
  const refresh = $("#refresh-runtime-surface");
  if (!status || !healthOutput || !pluginOutput || !refresh) return;
  refresh.disabled = !state.user || state.runtimeSurface.loading;

  if (state.runtimeSurface.loading && !state.runtimeSurface.loaded) {
    setDoctorStatus(status, "SKIPPED", true);
    status.textContent = t("runtime.health.pending");
    healthOutput.innerHTML = `<small>${escapeHtml(t("runtime.health.pending"))}</small>`;
  } else if (state.runtimeSurface.health?.status === "UP") {
    setDoctorStatus(status, "HEALTHY");
    status.textContent = "UP";
    healthOutput.innerHTML = `<strong>UP</strong><small>${escapeHtml(t("runtime.health.up"))}</small>`;
  } else if (state.runtimeSurface.healthError) {
    setDoctorStatus(status, "ERROR");
    healthOutput.innerHTML = `<small>${escapeHtml(t("runtime.health.error"))}</small>`;
  } else {
    setDoctorStatus(status, "SKIPPED", true);
    status.textContent = t("runtime.health.pending");
    healthOutput.innerHTML = `<small>${escapeHtml(t("runtime.health.pending"))}</small>`;
  }

  if (!can("system:plugins:read")) {
    pluginOutput.replaceChildren();
  } else if (state.runtimeSurface.loading && !state.runtimeSurface.loaded) {
    pluginOutput.innerHTML = `<small>${escapeHtml(t("runtime.plugins.loading"))}</small>`;
  } else if (state.runtimeSurface.pluginsError) {
    pluginOutput.innerHTML = `<small>${escapeHtml(t("runtime.plugins.error"))}</small>`;
  } else if (!state.runtimeSurface.loaded) {
    pluginOutput.innerHTML = `<small>${escapeHtml(t("runtime.plugins.pending"))}</small>`;
  } else {
    pluginOutput.innerHTML = state.runtimeSurface.plugins.map((plugin) => {
      const capabilities = (Array.isArray(plugin.capabilities) ? plugin.capabilities : []).map((capability) =>
        `<span class="plugin-capability">${escapeHtml(capability.type)} × ${escapeHtml(capability.count)}</span>`,
      ).join("");
      return `<article class="plugin-entry"><b>${escapeHtml(plugin.id)}</b><div class="plugin-capabilities">${capabilities}</div></article>`;
    }).join("") || `<small>${escapeHtml(t("runtime.plugins.empty"))}</small>`;
  }
}

async function loadRuntimeSurface(force = false) {
  if (!state.user || state.runtimeSurface.loading || (state.runtimeSurface.loaded && !force)) return;
  const generation = sessionGeneration;
  const sessionToken = state.token;
  const tenantId = state.user.tenantId;
  const mayReadPlugins = can("system:plugins:read");
  state.runtimeSurface.loading = true;
  state.runtimeSurface.healthError = false;
  state.runtimeSurface.pluginsError = false;
  renderRuntimeSurface();

  const [healthResult, pluginsResult] = await Promise.allSettled([
    v1Api(V1_HEALTH_PATH),
    mayReadPlugins ? v1Api(`${V1_PLUGINS_PATH}?limit=100`) : Promise.resolve({ items: [] }),
  ]);
  if (generation !== sessionGeneration || state.token !== sessionToken || state.user?.tenantId !== tenantId) return;

  state.runtimeSurface.health = healthResult.status === "fulfilled" && healthResult.value?.status === "UP"
    ? healthResult.value
    : null;
  state.runtimeSurface.healthError = state.runtimeSurface.health === null;
  if (mayReadPlugins) {
    const pluginPage = pluginsResult.status === "fulfilled" ? pluginsResult.value : null;
    state.runtimeSurface.plugins = Array.isArray(pluginPage?.items) ? pluginPage.items : [];
    state.runtimeSurface.pluginsError = !Array.isArray(pluginPage?.items);
  } else {
    state.runtimeSurface.plugins = [];
    state.runtimeSurface.pluginsError = false;
  }
  state.runtimeSurface.loading = false;
  state.runtimeSurface.loaded = true;
  renderRuntimeSurface();
}

async function runImmediateDoctor() {
  const documentId = state.selectedId;
  if (!documentId || !can("document:doctor")) return;
  const generation = state.doctor.contextGeneration;
  state.doctor.immediateReport = null;
  state.doctor.immediateLoading = true;
  renderDoctorPanel();
  try {
    const report = await v1Api(documentDoctorPath(documentId));
    if (state.selectedId !== documentId || state.doctor.documentId !== documentId || state.doctor.contextGeneration !== generation) return;
    if (!isDoctorReport(report, documentId)) throw new Error(t("error.v1.invalidResponse"));
    state.doctor.immediateReport = report;
  } finally {
    if (state.selectedId === documentId && state.doctor.documentId === documentId && state.doctor.contextGeneration === generation) {
      state.doctor.immediateLoading = false;
      renderDoctorPanel();
    }
  }
}

function queueDoctorTaskRefresh(generation, delay) {
  if (state.doctor.pollGeneration !== generation) return;
  state.doctor.pollTimer = window.setTimeout(() => refreshDoctorTask(generation), delay);
}

async function refreshDoctorTask(generation) {
  const documentId = state.doctor.documentId;
  const taskId = state.doctor.task?.id;
  if (!documentId || !taskId || state.doctor.pollGeneration !== generation) return;
  try {
    const result = await v1Api(`${documentDoctorPath(documentId)}/tasks/${encodeURIComponent(taskId)}`);
    if (state.doctor.pollGeneration !== generation || state.selectedId !== documentId || state.doctor.task?.id !== taskId) return;
    if (
      result?.task?.documentId !== documentId || result?.task?.id !== taskId ||
      !DOCTOR_TASK_STATUSES.has(result?.task?.status) ||
      (result.report !== null && !isDoctorReport(result.report, documentId)) ||
      (!DOCTOR_TASK_TERMINAL_STATUSES.has(result.task.status) && result.report !== null)
    ) throw new Error(t("error.v1.invalidResponse"));
    state.doctor.task = result.task;
    state.doctor.taskReport = result.report || null;
    state.doctor.taskLoading = false;
    state.doctor.taskRefreshFailure = false;
    state.doctor.pollFailures = 0;
    renderDoctorPanel();
    if (!DOCTOR_TASK_TERMINAL_STATUSES.has(normalizedDoctorTaskStatus(result.task.status))) {
      queueDoctorTaskRefresh(generation, 650);
    }
  } catch (error) {
    if (state.doctor.pollGeneration !== generation) return;
    state.doctor.pollFailures += 1;
    if (state.doctor.pollFailures <= 3) {
      queueDoctorTaskRefresh(generation, 750 * state.doctor.pollFailures);
      return;
    }
    state.doctor.taskLoading = false;
    state.doctor.taskRefreshFailure = true;
    renderDoctorPanel();
    reportRequestError(error);
  }
}

function startDoctorTaskPolling() {
  cancelDoctorPolling();
  const generation = ++doctorPollSequence;
  state.doctor.pollGeneration = generation;
  state.doctor.pollFailures = 0;
  queueDoctorTaskRefresh(generation, 200);
}

async function scheduleDoctorTask() {
  const documentId = state.selectedId;
  if (!documentId || !can("document:doctor")) return;
  if (
    state.doctor.taskRefreshFailure && state.doctor.documentId === documentId &&
    state.doctor.task?.documentId === documentId &&
    !DOCTOR_TASK_TERMINAL_STATUSES.has(normalizedDoctorTaskStatus(state.doctor.task.status))
  ) {
    state.doctor.taskLoading = true;
    state.doctor.taskRefreshFailure = false;
    renderDoctorPanel();
    startDoctorTaskPolling();
    return;
  }
  cancelDoctorPolling();
  const generation = state.doctor.contextGeneration;
  const idempotencyKey = state.doctor.scheduleKey || doctorRequestKey();
  state.doctor.scheduleKey = idempotencyKey;
  state.doctor.task = null;
  state.doctor.taskReport = null;
  state.doctor.taskLoading = true;
  state.doctor.taskRefreshFailure = false;
  renderDoctorPanel();
  try {
    const scheduled = await v1Api(`${documentDoctorPath(documentId)}/tasks`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    });
    if (state.selectedId !== documentId || state.doctor.documentId !== documentId || state.doctor.contextGeneration !== generation) return;
    if (!scheduled?.taskId || scheduled.documentId !== documentId || scheduled.status !== "PENDING") {
      throw new Error(t("error.v1.invalidResponse"));
    }
    state.doctor.task = {
      id: scheduled.taskId,
      documentId: scheduled.documentId,
      status: normalizedDoctorTaskStatus(scheduled.status),
      createdTime: 0,
      updatedTime: 0,
    };
    state.doctor.scheduleKey = null;
    notice(interpolate("notice.doctorScheduled", { taskId: scheduled.taskId }));
    startDoctorTaskPolling();
  } catch (error) {
    if (state.selectedId === documentId && state.doctor.documentId === documentId && state.doctor.contextGeneration === generation) {
      state.doctor.taskLoading = false;
      state.doctor.taskRefreshFailure = false;
      renderDoctorPanel();
    }
    throw error;
  }
}

async function runSystemDoctor() {
  if (!can("system:doctor:read")) return;
  const sessionToken = state.token;
  const tenantId = state.user?.tenantId;
  const generation = state.doctor.contextGeneration;
  state.doctor.systemReport = null;
  state.doctor.systemLoading = true;
  renderDoctorPanel();
  try {
    const report = await v1Api(V1_SYSTEM_DOCTOR_PATH);
    if (!state.user || state.token !== sessionToken || state.user.tenantId !== tenantId || state.doctor.contextGeneration !== generation) return;
    if (!isDoctorReport(report)) throw new Error(t("error.v1.invalidResponse"));
    state.doctor.systemReport = report;
  } finally {
    if (state.user && state.token === sessionToken && state.user.tenantId === tenantId && state.doctor.contextGeneration === generation) {
      state.doctor.systemLoading = false;
      renderDoctorPanel();
    }
  }
}

function renderInspector() {
  const detail = state.detail;
  const document = detail.document;
  const mayReadAudit = can("document:audit") && can("document:read");
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
  $("#workflow-list").innerHTML = state.workflowHistory.map((workflow) => {
    const evidenceWorkflow = mayReadAudit
      ? state.workflowDecisionEvidence.find((candidate) => candidate.id === workflow.id)
      : null;
    const tasks = workflow.tasks.map((task) => {
      const evidence = evidenceWorkflow?.tasks?.find((candidate) => candidate.id === task.id);
      let decision = "";
      if (task.state !== "PENDING" && mayReadAudit) {
        if (evidence?.decisionEvidenceRecorded) {
          const actorName = evidence.decisionOperatorName || t("actor.unnamed");
          decision = ` · ${escapeHtml(t("workflow.decisionBy"))} ${escapeHtml(actorName)} · ${escapeHtml(evidence.decisionOperatorId)} · ${escapeHtml(formatTime(evidence.decidedTime))}`;
        } else {
          decision = ` · ${escapeHtml(t("workflow.decisionUnknown"))}`;
        }
      }
      return `${escapeHtml(task.id)} · ${escapeHtml(localizedState(task.state))} · ${escapeHtml(formatTime(task.updatedTime))}${decision}`;
    }).join("<br />");
    return evidenceItem(`${localized("workflow.type", workflow.workflowType)} / ${localizedState(workflow.state)}`, tasks);
  }).join("") || emptyEvidence("empty.workflow");
  $("#delivery-list").innerHTML = detail.deliveries.map((delivery) => {
    const safeStatus = state.syncStatus?.deliveryTargets?.find((target) => target.deliveryId === delivery.id);
    const responsibility = delivery.ownerRef ? ` · ${escapeHtml(delivery.ownerRef)}` : "";
    const error = delivery.errorMessage ? `<small class="delivery-error">${escapeHtml(delivery.errorMessage)}</small>` : "";
    const retry = delivery.retryCount ? ` · ${escapeHtml(interpolate("delivery.retries", { count: delivery.retryCount }))}` : "";
    const removal = delivery.removalStatus && delivery.removalStatus !== "NOT_REQUESTED"
      ? `<small>${escapeHtml(t("delivery.removal"))}: ${escapeHtml(localized("delivery.removal.status", delivery.removalStatus))}${delivery.removalRetryCount ? ` · ${escapeHtml(interpolate("delivery.retries", { count: delivery.removalRetryCount }))}` : ""}${delivery.removalErrorMessage ? ` · ${escapeHtml(delivery.removalErrorMessage)}` : ""}</small>` : "";
    const retryOperation = safeStatus?.removalRetryable ? "REMOVAL" : (safeStatus?.deliveryRetryable ? "DELIVERY" : null);
    const canRetry = retryOperation && can("document:delivery:retry");
    const manualRetry = canRetry
      ? `<button class="delivery-retry" type="button" data-delivery-retry="${escapeHtml(delivery.id)}" data-retry-operation="${retryOperation}">${escapeHtml(t(retryOperation === "REMOVAL" ? "action.retryRemoval" : "action.retryDelivery"))}</button>` : "";
    return `<article class="delivery-card ${escapeHtml(delivery.status)}"><div><span class="delivery-requirement">${escapeHtml(localized("delivery.requirement", delivery.requirement))}</span><b>${escapeHtml(delivery.displayName)}</b><small>${escapeHtml(t("delivery.generation"))} ${escapeHtml(delivery.deliveryGeneration)} · ${escapeHtml(delivery.connectorId)}${responsibility}</small></div><div class="delivery-status"><strong>${escapeHtml(localized("delivery.status", delivery.status))}</strong><small>${escapeHtml(delivery.externalId || "—")}${retry}</small>${error}${removal}${manualRetry}</div></article>`;
  }).join("") || emptyEvidence("empty.delivery");
  $("#delivery-list").querySelectorAll("[data-delivery-retry]").forEach((button) => button.addEventListener("click", () => retryDelivery(button.dataset.deliveryRetry, button.dataset.retryOperation)));
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
  $("#sync-list").innerHTML = detail.syncRecords.map((sync) => evidenceItem(
    `${localizedState(sync.status)} / ${sync.connectorName}`,
    `${escapeHtml(sync.externalId || "—")}${sync.errorMessage ? ` · ${escapeHtml(sync.errorMessage)}` : ""}`,
  )).join("") || emptyEvidence("empty.sync");
  $("#audit-section").classList.toggle("hidden", !mayReadAudit);
  $("#audit-list").innerHTML = (mayReadAudit ? state.auditLogs : []).map((audit) => {
    const actorName = audit.operatorName || (audit.operatorId ? t("actor.unnamed") : t("actor.system"));
    const actorId = audit.operatorId ? ` · ${escapeHtml(audit.operatorId)}` : "";
    const trace = audit.traceId
      ? ` · ${escapeHtml(t("operation.trace"))}: ${escapeHtml(audit.traceId)}`
      : ` · ${escapeHtml(t("operation.noTrace"))}`;
    return evidenceItem(localizedAudit(audit.action), `${escapeHtml(actorName)}${actorId} · ${escapeHtml(formatTime(audit.createdTime))}${trace}`);
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
  if (can("document:edit") && state.selectedFolderId && document.folderId !== state.selectedFolderId) {
    actions.push(actionButton("action.moveToFolder", "moveFolder"));
  }
  if (["DRAFT", "REJECTED"].includes(document.lifecycleState) && can("document:version:add")) actions.push(actionButton("action.addVersion", "version"));
  if (document.lifecycleState === "DRAFT" && can("document:submit")) {
    actions.push(actionButton("action.submit", "submit"), actionButton("action.submitDualControl", "submitDualControl"));
  }
  const pending = pendingTaskForDocument(document.id);
  if (document.lifecycleState === "PENDING_REVIEW" && pending && can("document:audit")) {
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
      activatePanel("doctor");
      await runImmediateDoctor();
      return;
    }
    if (action === "scheduleDoctor") {
      activatePanel("doctor");
      await scheduleDoctorTask();
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
      const pending = pendingTaskForDocument(id);
      if (!pending) throw new Error(t("workflow.inbox.noneForDocument"));
      await decideWorkflowTask(pending, action, action === "approve" ? ($("#delivery-profile").value || null) : null);
    }
    if (["revise", "restore", "offline", "archive"].includes(action)) {
      await v1Api(`${V1_DOCUMENTS_PATH}/${id}/${action}`, lifecycleRequest(action));
    }
    await refreshDocuments();
  } catch (error) {
    reportRequestError(error);
  }
}

async function retryDelivery(deliveryId, operation) {
  try {
    const suffix = operation === "REMOVAL" ? "/removal/retry" : "/retry";
    await v1Api(`${V1_DOCUMENTS_PATH}/${state.selectedId}/deliveries/${deliveryId}${suffix}`, lifecycleRequest(`delivery-${operation?.toLowerCase() || "retry"}`));
    notice(t("notice.deliveryRetried"));
    await refreshDocuments();
    loadPlatform();
  } catch (error) {
    reportRequestError(error);
  }
}

async function downloadDocument(versionId, fileName) {
  try {
    const headers = new Headers();
    if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
    const result = await fetchForCurrentSession(
      `${V1_DOCUMENTS_PATH}/${state.selectedId}/versions/${versionId}/content`,
      { headers },
      async (response) => {
        if (response.ok) return { ok: true, blob: await response.blob() };
        const text = await response.text();
        return { ok: false, status: response.status, payload: text ? safeJson(text) : null };
      },
    );
    if (!result.ok) {
      throw new Error(localizedApiError(result.payload, result.status));
    }
    const objectUrl = URL.createObjectURL(result.blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = fileName || "fileweft-download";
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
    notice(t("notice.download"));
  } catch (error) {
    reportRequestError(error);
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
  if (!state.workflowTasks.length) {
    notice(t("notice.noPending"));
    return;
  }
  activatePanel("workflow");
}

async function createFixture(fixtureId) {
  if (!can("document:create")) return;
  const fixture = FIXTURES.find((item) => item.id === fixtureId);
  try {
    const fixtureResponse = await fetchForCurrentSession(fixture.path, {}, async (response) => ({
      ok: response.ok,
      status: response.status,
      blob: response.ok ? await response.blob() : null,
    }));
    if (!fixtureResponse.ok) throw new Error(`Fixture download failed (${fixtureResponse.status})`);
    const blob = fixtureResponse.blob;
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
    reportRequestError(error);
  }
}

async function loadPlatform() {
  const generation = sessionGeneration;
  if (!state.selectedId || !state.user || !can(DOWNSTREAM_MIRROR_CAPABILITY)) {
    $("#platform-output").textContent = t("platform.empty");
    return;
  }
  const documentId = state.selectedId;
  try {
    const deliveries = state.detail?.deliveries || [];
    if (!deliveries.length) {
      $("#platform-output").textContent = t("platform.empty");
      return;
    }
    const records = await api(`/api/documents/${documentId}/platform-mirror`);
    if (generation !== sessionGeneration || state.selectedId !== documentId || !can(DOWNSTREAM_MIRROR_CAPABILITY)) return;
    $("#platform-output").textContent = JSON.stringify(records, null, 2);
  } catch (error) {
    if (generation === sessionGeneration && !isRequestCancellation(error) && can(DOWNSTREAM_MIRROR_CAPABILITY)) {
      $("#platform-output").textContent = error.message;
    }
  }
}

function resumableCheckpointKeySegment(value) {
  const bytes = new TextEncoder().encode(String(value));
  let binary = "";
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function resumableCheckpointKey() {
  const tenantId = state.user?.tenantId;
  const userId = state.user?.userId;
  if (typeof tenantId !== "string" || !tenantId || typeof userId !== "string" || !userId) return null;
  return `${RESUMABLE_CHECKPOINT_PREFIX}.tenant.${resumableCheckpointKeySegment(tenantId)}.user.${resumableCheckpointKeySegment(userId)}`;
}

function removeLegacyTenantCheckpoint(tenantId) {
  if (typeof tenantId !== "string" || !tenantId) return;
  try {
    localStorage.removeItem(`${RESUMABLE_CHECKPOINT_PREFIX}.${tenantId}`);
  } catch {
    // Storage may be unavailable in hardened browser profiles; login itself must still succeed.
  }
}

function readResumableCheckpoint() {
  try {
    const key = resumableCheckpointKey();
    if (!key) return null;
    const checkpoint = JSON.parse(localStorage.getItem(key) || "null");
    if (!checkpoint || checkpoint.version !== 1 || typeof checkpoint.idempotencyKey !== "string" ||
      typeof checkpoint.fileName !== "string" || !Number.isSafeInteger(checkpoint.contentLength) || checkpoint.contentLength <= 0 ||
      !Number.isSafeInteger(checkpoint.chunkSizeBytes) || checkpoint.chunkSizeBytes < MINIMUM_RESUMABLE_CHUNK_BYTES) return null;
    return checkpoint;
  } catch {
    return null;
  }
}

function writeResumableCheckpoint(checkpoint) {
  const key = resumableCheckpointKey();
  if (!key) throw new Error("A stable authenticated user id is required for a resumable upload checkpoint.");
  localStorage.setItem(key, JSON.stringify(checkpoint));
}

function clearResumableCheckpoint() {
  const key = resumableCheckpointKey();
  if (key) localStorage.removeItem(key);
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
  const generation = sessionGeneration;
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
    if (generation !== sessionGeneration || isRequestCancellation(error)) return;
    failureMessage = error.message;
    setResumableStatus(error.message, "active");
    reportRequestError(error);
  } finally {
    if (generation !== sessionGeneration) return;
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
  const generation = sessionGeneration;
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
    if (generation !== sessionGeneration || isRequestCancellation(error)) return;
    failureMessage = error.message;
    setResumableStatus(error.message, "active");
    reportRequestError(error);
  } finally {
    if (generation !== sessionGeneration) return;
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
    reportRequestError(error);
  }
}

function activatePanel(panel) {
  const target = (panel === "doctor" && !can("document:doctor")) ||
    (panel === "workflow" && !can("document:audit")) ||
    (panel === "platform" && !can(DOWNSTREAM_MIRROR_CAPABILITY)) ? "documents" : panel;
  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.panel === target));
  ["documents", "workflow", "fixtures", "platform", "doctor", "uploads"].forEach((name) => $(`#${name}-panel`).classList.toggle("hidden", name !== target));
  if (target === "platform") loadPlatform();
  if (target === "workflow") renderWorkflowInbox();
  if (target === "fixtures") renderFixtures();
  if (target === "uploads") renderResumableUpload();
  if (target === "doctor") {
    renderDoctorPanel();
    loadRuntimeSurface().catch(reportRequestError);
  }
}

async function processOutbox() {
  try {
    const result = await api("/api/outbox/process?limit=20", { method: "POST" });
    notice(interpolate("notice.outbox", result));
    await refreshDocuments();
    loadPlatform();
  } catch (error) {
    reportRequestError(error);
  }
}

async function processTasks() {
  try {
    const result = await api("/api/tasks/process?limit=20", { method: "POST" });
    notice(interpolate("notice.tasks", result));
    await refreshDocuments();
  } catch (error) {
    reportRequestError(error);
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
  try { await login(form.get("username"), form.get("password")); } catch (error) { reportRequestError(error); }
});
document.querySelectorAll(".preset").forEach((button) => button.addEventListener("click", () => { $("#username").value = button.dataset.user; $("#password").value = button.dataset.password; }));
document.querySelectorAll("[data-locale]").forEach((button) => button.addEventListener("click", () => setLocale(button.dataset.locale)));
$("#refresh").addEventListener("click", () => refreshDocuments().catch(reportRequestError));
$("#process-outbox").addEventListener("click", processOutbox);
$("#process-tasks").addEventListener("click", processTasks);
$("#run-doctor").addEventListener("click", () => runImmediateDoctor().catch(reportRequestError));
$("#schedule-doctor").addEventListener("click", () => scheduleDoctorTask().catch(reportRequestError));
$("#run-system-doctor").addEventListener("click", () => runSystemDoctor().catch(reportRequestError));
$("#refresh-runtime-surface").addEventListener("click", () => loadRuntimeSurface(true).catch(reportRequestError));
$("#refresh-workflow-inbox").addEventListener("click", () => refreshDocuments().catch(reportRequestError));
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
  } catch (error) { reportRequestError(error); }
});
$("#rename-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    await v1Api(`${V1_DOCUMENTS_PATH}/${state.selectedId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: new FormData(form).get("title") }) });
    form.reset();
    await refreshDocuments();
  } catch (error) { reportRequestError(error); }
});
$("#version-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    await v1Api(`${V1_DOCUMENTS_PATH}/${state.selectedId}/versions`, { method: "POST", body: new FormData(form) });
    form.reset();
    await refreshDocuments();
  } catch (error) { reportRequestError(error); }
});
$("#resumable-upload-form").addEventListener("submit", startOrResumeUpload);
$("#resumable-abort").addEventListener("click", abortResumableUpload);
$("#resumable-maintenance").addEventListener("click", loadStalledResumableCompletions);
$("#logout").addEventListener("click", () => {
  const token = state.token;
  invalidateSession();
  setLoginBusy(false);
  if (token) {
    api("/api/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  }
});
document.querySelectorAll(".nav-item").forEach((button) => button.addEventListener("click", () => activatePanel(button.dataset.panel)));
window.addEventListener("pageshow", (event) => {
  if (!event.persisted) return;
  invalidateSession();
  setLoginBusy(false);
});

applyTranslations();
