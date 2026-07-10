const state = { token: null, user: null, documents: [], selectedId: null, detail: null };
const $ = (selector) => document.querySelector(selector);

const api = async (path, options = {}) => {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  const payload = text ? safeJson(text) : null;
  if (!response.ok) throw new Error(payload?.message || `请求失败（${response.status}）`);
  return payload;
};
const safeJson = (text) => { try { return JSON.parse(text); } catch { return text; } };
const escapeHtml = (value = "") => String(value).replace(/[&<>"]/g, (char) => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" }[char]));
const time = (value) => value ? new Date(Number(value)).toLocaleString("zh-CN", { hour12: false }) : "—";

function notice(message, type = "") {
  const element = $("#notice");
  element.textContent = message;
  element.className = `notice ${type}`;
  setTimeout(() => element.classList.add("hidden"), 5000);
}

async function login(username, password) {
  const result = await api("/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }) });
  state.token = result.token;
  state.user = result;
  $("#login-view").classList.add("hidden");
  $("#app-view").classList.remove("hidden");
  $("#identity-name").textContent = result.displayName;
  $("#identity-meta").textContent = `${result.tenantId} / ${result.username}`;
  $("#identity-role").textContent = result.role;
  $("#metric-tenant").textContent = result.tenantId;
  await refreshDocuments();
  notice("身份已接入，开始验收文档链路。");
}

async function refreshDocuments() {
  state.documents = await api("/api/documents?limit=60");
  renderDocuments();
  updateMetrics();
  if (state.selectedId && state.documents.some((doc) => doc.id === state.selectedId)) await selectDocument(state.selectedId);
}

function updateMetrics() {
  $("#metric-documents").textContent = state.documents.length;
  $("#metric-review").textContent = state.documents.filter((doc) => doc.lifecycleState === "PENDING_REVIEW").length;
  $("#metric-sync").textContent = state.documents.filter((doc) => doc.lifecycleState === "SYNC_ERROR").length;
}

function renderDocuments() {
  const list = $("#document-list");
  if (!state.documents.length) {
    list.innerHTML = `<div class="evidence-item"><b>尚无文档</b><small>从“新建草稿”开始一条完整的上传、审批与同步链路。</small></div>`;
    return;
  }
  list.innerHTML = state.documents.map((doc) => `
    <button class="document-row ${doc.id === state.selectedId ? "selected" : ""}" data-document-id="${doc.id}">
      <span><b>${escapeHtml(doc.documentNumber)}</b><small>${escapeHtml(doc.title)}</small></span>
      <span class="state-tag ${doc.lifecycleState}">${doc.lifecycleState}</span>
      <span><small>${time(doc.updatedTime)}</small></span>
      <span><small>${escapeHtml(doc.currentVersionId || "—")}</small></span>
    </button>`).join("");
  list.querySelectorAll("[data-document-id]").forEach((button) => button.addEventListener("click", () => selectDocument(button.dataset.documentId)));
}

async function selectDocument(documentId) {
  state.selectedId = documentId;
  state.detail = await api(`/api/documents/${documentId}`);
  $("#empty-inspector").classList.add("hidden");
  $("#document-inspector").classList.remove("hidden");
  $("#selection-count").textContent = "已选择";
  renderDocuments();
  renderInspector();
  loadPlatform();
}

function renderInspector() {
  const detail = state.detail;
  const document = detail.document;
  $("#selected-number").textContent = document.documentNumber;
  $("#selected-title").textContent = document.title;
  $("#selected-state").textContent = document.lifecycleState;
  $("#selected-state").className = `state-tag ${document.lifecycleState}`;
  $("#version-list").innerHTML = detail.versions.map((version) => item(version.versionNumber, `${escapeHtml(version.fileName)} · ${escapeHtml(version.contentLength)} bytes · ${escapeHtml(version.contentHash || "无摘要")}`)).join("") || empty("尚无版本");
  $("#workflow-list").innerHTML = detail.workflows.map((workflow) => item(`${workflow.type} / ${workflow.state}`, workflow.tasks.map((task) => `${escapeHtml(task.assigneeId || "未指派")} · ${escapeHtml(task.state)}${task.comment ? ` · ${escapeHtml(task.comment)}` : ""}`).join("<br />"))).join("") || empty("尚无审批流");
  $("#sync-list").innerHTML = detail.syncRecords.map((sync) => item(`${sync.status} / ${sync.connectorName}`, `${escapeHtml(sync.externalId || "无外部 ID")}${sync.errorMessage ? ` · ${escapeHtml(sync.errorMessage)}` : ""}`)).join("") || empty("尚无同步记录");
  $("#audit-list").innerHTML = detail.audits.map((audit) => item(audit.action, `${escapeHtml(audit.operatorId || "SYSTEM")} · ${time(audit.createdTime)}`)).join("") || empty("尚无审计记录");
  renderActions();
}
const item = (title, text) => `<div class="evidence-item"><b>${escapeHtml(title)}</b><small>${text}</small></div>`;
const empty = (text) => `<div class="evidence-item"><small>${text}</small></div>`;

function renderActions() {
  const document = state.detail.document;
  const isEditor = state.user.role === "EDITOR" || state.user.role === "ADMIN";
  const isReviewer = state.user.role === "REVIEWER" || state.user.role === "ADMIN";
  const actions = [button("诊断 Doctor", "doctor"), button("重命名", "rename")];
  if (["DRAFT", "REJECTED"].includes(document.lifecycleState) && isEditor) actions.push(button("追加版本", "version"));
  if (document.lifecycleState === "DRAFT" && isEditor) actions.push(button("提交审批", "submit"));
  const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
  if (document.lifecycleState === "PENDING_REVIEW" && workflow && isReviewer) { actions.push(button("通过", "approve"), button("驳回", "reject")); }
  if (document.lifecycleState === "REJECTED" && isEditor) actions.push(button("修订为草稿", "revise"));
  if (document.lifecycleState === "PUBLISHED" && state.user.role === "ADMIN") { actions.push(button("下线", "offline"), button("归档", "archive")); }
  $("#document-actions").innerHTML = actions.join("");
  $("#document-actions").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => runAction(button.dataset.action)));
}
const button = (label, action) => `<button data-action="${action}">${label}</button>`;

async function runAction(action) {
  const id = state.selectedId;
  try {
    if (action === "rename") return $("#rename-form").classList.toggle("hidden");
    if (action === "version") return $("#version-form").classList.toggle("hidden");
    if (action === "doctor") { const report = await api(`/api/documents/${id}/doctor`); $("#doctor-output").textContent = JSON.stringify(report, null, 2); activatePanel("doctor"); return; }
    if (action === "submit") { await api(`/api/documents/${id}/submit`, json({ reviewerId: "alpha-reviewer" })); notice("已提交给 alpha-reviewer。", ""); }
    if (["approve", "reject"].includes(action)) {
      const workflow = state.detail.workflows.find((item) => item.state === "PENDING");
      const task = workflow.tasks.find((item) => item.state === "PENDING");
      await api(`/api/documents/workflows/${workflow.id}/tasks/${task.id}/${action}`, json({ comment: action === "approve" ? "开发验收通过" : "需要修订" }));
      notice(action === "approve" ? "审批已通过，等待 Outbox 同步。" : "已驳回，文档回到修订路径。", "");
    }
    if (["revise", "offline", "archive"].includes(action)) await api(`/api/documents/${id}/${action}`, { method: "POST" });
    await refreshDocuments();
  } catch (error) { notice(error.message, "error"); }
}
const json = (body) => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

async function loadPlatform() {
  if (!state.selectedId) return;
  try {
    const response = await fetch(`/platform/v1/documents/${state.user.tenantId}/${state.selectedId}`);
    const text = await response.text();
    $("#platform-output").textContent = response.ok ? JSON.stringify(safeJson(text), null, 2) : "下游暂未收到该文档。发布并处理 Outbox 后会出现镜像记录。";
  } catch (error) { $("#platform-output").textContent = `下游不可达：${error.message}`; }
}

$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try { await login(form.get("username"), form.get("password")); } catch (error) { notice(error.message, "error"); }
});
document.querySelectorAll(".preset").forEach((button) => button.addEventListener("click", () => { $("#username").value = button.dataset.user; $("#password").value = button.dataset.password; }));
$("#refresh").addEventListener("click", () => refreshDocuments().catch((error) => notice(error.message, "error")));
$("#open-create").addEventListener("click", () => $("#create-drawer").classList.remove("hidden"));
$("#close-create").addEventListener("click", () => $("#create-drawer").classList.add("hidden"));
$("#create-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const detail = await api("/api/documents", { method: "POST", body: new FormData(event.currentTarget) });
    $("#create-drawer").classList.add("hidden"); event.currentTarget.reset(); state.selectedId = detail.document.id; await refreshDocuments(); notice("草稿和首个版本已写入。", "");
  } catch (error) { notice(error.message, "error"); }
});
$("#rename-form").addEventListener("submit", async (event) => { event.preventDefault(); try { await api(`/api/documents/${state.selectedId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: new FormData(event.currentTarget).get("title") }) }); event.currentTarget.reset(); await refreshDocuments(); } catch (error) { notice(error.message, "error"); } });
$("#version-form").addEventListener("submit", async (event) => { event.preventDefault(); try { await api(`/api/documents/${state.selectedId}/versions`, { method: "POST", body: new FormData(event.currentTarget) }); event.currentTarget.reset(); await refreshDocuments(); } catch (error) { notice(error.message, "error"); } });
$("#process-outbox").addEventListener("click", async () => { try { const result = await api("/api/outbox/process?limit=20", { method: "POST" }); notice(`Outbox：认领 ${result.claimed}，成功 ${result.succeeded}，重试 ${result.retried}，失败 ${result.failed}`); await refreshDocuments(); loadPlatform(); } catch (error) { notice(error.message, "error"); } });
$("#logout").addEventListener("click", async () => { try { await api("/api/auth/logout", { method: "POST" }); } finally { state.token = null; state.user = null; state.selectedId = null; $("#app-view").classList.add("hidden"); $("#login-view").classList.remove("hidden"); } });
document.querySelectorAll(".nav-item").forEach((button) => button.addEventListener("click", () => activatePanel(button.dataset.panel)));
function activatePanel(panel) { document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.panel === panel)); $("#documents-panel").classList.toggle("hidden", panel !== "documents"); $("#platform-panel").classList.toggle("hidden", panel !== "platform"); $("#doctor-panel").classList.toggle("hidden", panel !== "doctor"); if (panel === "platform") loadPlatform(); }
