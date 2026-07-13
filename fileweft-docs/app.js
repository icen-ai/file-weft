import { defaultRoute, groups, pages, ui } from "./content.js";
import { escapeHtml, markdownToHtml, parseFrontmatter, stripHtml } from "./markdown.js";

const state = {
  locale: readLocale(),
  route: defaultRoute,
  activeSearchIndex: 0,
  searchMatches: [],
};

const elements = {
  article: document.querySelector("#doc-content"),
  breadcrumbs: document.querySelector("#breadcrumbs"),
  nav: document.querySelector("#primary-nav"),
  toc: document.querySelector("#page-toc"),
  searchDialog: document.querySelector("#search-dialog"),
  searchInput: document.querySelector("#search-input"),
  searchResults: document.querySelector("#search-results"),
  searchTrigger: document.querySelector("#search-trigger"),
  searchClose: document.querySelector("#search-close"),
  mobileSearch: document.querySelector("#mobile-search"),
  navToggle: document.querySelector("#nav-toggle"),
  navScrim: document.querySelector("#nav-scrim"),
  toast: document.querySelector("#toast"),
};

const routes = orderedRoutes();
const groupById = new Map(groups.map((group) => [group.id, group]));
const pageCache = {};

function readLocale() {
  try {
    return localStorage.getItem("fileweft-docs-locale") === "zh" ? "zh" : "en";
  } catch {
    return "en";
  }
}

function saveLocale(locale) {
  try {
    localStorage.setItem("fileweft-docs-locale", locale);
  } catch {
    // Preferences remain session-local when storage is unavailable.
  }
}

function orderedRoutes() {
  return Object.keys(pages).sort((a, b) => {
    const pa = pages[a];
    const pb = pages[b];
    const ga = groups.findIndex((g) => g.id === pa.group);
    const gb = groups.findIndex((g) => g.id === pb.group);
    if (ga !== gb) return ga - gb;
    return pa.order - pb.order;
  });
}

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, "");
  const [route, query = ""] = raw.split("?");
  const safeRoute = pages[route] ? route : defaultRoute;
  const section = new URLSearchParams(query).get("section") || "";
  return { route: safeRoute, section };
}

function slug(value) {
  return value
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^\p{Letter}\p{Number}]+/gu, "-")
    .replace(/^-|-$/g, "") || "section";
}

async function loadPage(route, locale) {
  const file = pages[route][locale].file;
  const response = await fetch(file);
  if (!response.ok) {
    throw new Error(`Failed to load documentation page: ${file}`);
  }
  const source = await response.text();
  const { meta, body } = parseFrontmatter(source);
  const html = meta.format === "markdown" ? markdownToHtml(body) : body;
  return { meta, html, text: stripHtml(html) };
}

async function loadAllPages() {
  await Promise.all(
    routes.flatMap((route) => ["en", "zh"].map(async (locale) => {
      pageCache[route] = pageCache[route] || {};
      pageCache[route][locale] = await loadPage(route, locale);
    })),
  );
}

function setLocale(locale) {
  state.locale = locale === "zh" ? "zh" : "en";
  saveLocale(state.locale);
  document.documentElement.lang = state.locale === "zh" ? "zh-CN" : "en";
  document.querySelectorAll("[data-locale]").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.locale === state.locale));
  });
  document.querySelectorAll("[data-ui]").forEach((node) => {
    const key = node.dataset.ui;
    if (ui[state.locale][key]) node.textContent = ui[state.locale][key];
  });
  document.querySelectorAll("[data-aria-ui]").forEach((node) => {
    const key = node.dataset.ariaUi;
    if (ui[state.locale][key]) node.setAttribute("aria-label", ui[state.locale][key]);
  });
  elements.searchInput.placeholder = ui[state.locale].searchPlaceholder;
  renderNavigation();
  renderPage(parseHash().section);
  if (elements.searchDialog.open) runSearch(elements.searchInput.value);
}

function renderNavigation() {
  const fragment = document.createDocumentFragment();
  groups.forEach((group) => {
    const section = document.createElement("section");
    section.className = "nav-group";
    const title = document.createElement("h2");
    title.className = "nav-group-title";
    title.textContent = `${group.index} / ${group[state.locale]}`;
    section.append(title);

    routes.filter((route) => pages[route].group === group.id).forEach((route, index) => {
      const link = document.createElement("a");
      link.className = "nav-link";
      link.href = `#/${route}`;
      link.dataset.route = route;
      if (route === state.route) link.setAttribute("aria-current", "page");
      const number = document.createElement("span");
      number.textContent = String(index + 1).padStart(2, "0");
      const label = document.createElement("b");
      label.textContent = pages[route][state.locale].nav;
      link.append(number, label);
      section.append(link);
    });
    fragment.append(section);
  });
  elements.nav.replaceChildren(fragment);
}

function renderPage(sectionTarget = "") {
  const entry = pages[state.route];
  const cache = pageCache[state.route]?.[state.locale];
  if (!cache) {
    elements.article.innerHTML = `<p>Loading…</p>`;
    return;
  }
  const meta = entry[state.locale];
  const group = groupById.get(entry.group);
  document.title = `${meta.nav} — FileWeft`;

  const kicker = document.createElement("div");
  kicker.className = "doc-kicker";
  kicker.dataset.index = group.index;
  kicker.textContent = group[state.locale];

  const heading = document.createElement("h1");
  heading.textContent = meta.title;
  const lead = document.createElement("p");
  lead.className = "lead";
  lead.textContent = meta.lead;
  const body = document.createElement("div");
  body.className = "article-body";
  body.innerHTML = cache.html;

  // Wrap each h2 and its following content into a section with a stable id.
  const headings = Array.from(body.querySelectorAll("h2"));
  headings.forEach((h2, index) => {
    h2.setAttribute("data-step", String(index + 1).padStart(2, "0"));
    const section = document.createElement("section");
    section.id = slug(h2.textContent);
    const parent = h2.parentNode;
    parent.insertBefore(section, h2);
    section.appendChild(h2);
    let sibling = section.nextSibling;
    while (sibling && !(sibling.nodeType === 1 && sibling.tagName === "H2")) {
      const next = sibling.nextSibling;
      section.appendChild(sibling);
      sibling = next;
    }
  });

  const currentIndex = routes.indexOf(state.route);
  if (currentIndex >= 0 && currentIndex < routes.length - 1) {
    const nextRoute = routes[currentIndex + 1];
    const nextLink = document.createElement("a");
    nextLink.className = "next-page";
    nextLink.href = `#/${nextRoute}`;
    const nextCopy = document.createElement("div");
    const nextSmall = document.createElement("small");
    nextSmall.textContent = ui[state.locale].next;
    const nextTitle = document.createElement("b");
    nextTitle.textContent = pages[nextRoute][state.locale].nav;
    nextCopy.append(nextSmall, nextTitle);
    const arrow = document.createElement("span");
    arrow.textContent = "→";
    nextLink.append(nextCopy, arrow);
    body.append(nextLink);
  }

  elements.article.replaceChildren(kicker, heading, lead, body);
  enhanceCodeBlocks();
  renderBreadcrumbs(group, meta);
  renderToc(body);
  renderNavigation();
  closeMobileNav();

  requestAnimationFrame(() => {
    const target = sectionTarget && document.getElementById(sectionTarget);
    if (target) target.scrollIntoView({ block: "start" });
    else window.scrollTo({ top: 0, behavior: "auto" });
  });
}

function renderBreadcrumbs(group, meta) {
  elements.breadcrumbs.replaceChildren();
  const home = document.createElement("a");
  home.href = `#/${defaultRoute}`;
  home.textContent = "FileWeft";
  const groupName = document.createTextNode(` / ${group[state.locale]} / `);
  const current = document.createElement("b");
  current.textContent = meta.nav;
  elements.breadcrumbs.append(home, groupName, current);
}

function renderToc(body) {
  const fragment = document.createDocumentFragment();
  body.querySelectorAll("h2").forEach((heading) => {
    const link = document.createElement("a");
    link.href = `#/${state.route}?section=${encodeURIComponent(slug(heading.textContent))}`;
    link.textContent = heading.textContent;
    fragment.append(link);
  });
  elements.toc.replaceChildren(fragment);
}

function enhanceCodeBlocks() {
  elements.article.querySelectorAll(".code-label").forEach((label) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "copy-button";
    button.textContent = ui[state.locale].copy;
    button.addEventListener("click", async () => {
      const source = label.parentElement?.querySelector("code")?.textContent || "";
      const success = await copyText(source);
      button.textContent = success ? ui[state.locale].copied : ui[state.locale].copyFailed;
      showToast(button.textContent);
      setTimeout(() => { button.textContent = ui[state.locale].copy; }, 1500);
    });
    label.append(button);
  });
}

async function copyText(value) {
  try {
    await navigator.clipboard.writeText(value);
    return true;
  } catch {
    const field = document.createElement("textarea");
    field.value = value;
    field.style.position = "fixed";
    field.style.opacity = "0";
    document.body.append(field);
    field.select();
    const copied = document.execCommand("copy");
    field.remove();
    return copied;
  }
}

let toastTimer;
function showToast(message) {
  clearTimeout(toastTimer);
  elements.toast.textContent = message;
  elements.toast.classList.add("show");
  toastTimer = setTimeout(() => elements.toast.classList.remove("show"), 1600);
}

function searchIndex() {
  return routes.map((route) => {
    const entry = pages[route];
    const meta = entry[state.locale];
    const cache = pageCache[route]?.[state.locale];
    const text = cache?.text || "";
    return {
      route,
      title: meta.nav,
      group: groupById.get(entry.group)[state.locale],
      text: `${meta.title} ${meta.lead} ${text}`.toLocaleLowerCase(state.locale === "zh" ? "zh-CN" : "en-US"),
    };
  });
}

function runSearch(query) {
  const normalized = query.trim().toLocaleLowerCase(state.locale === "zh" ? "zh-CN" : "en-US");
  const tokens = normalized.split(/\s+/).filter(Boolean);
  state.searchMatches = searchIndex()
    .map((item) => ({ ...item, score: tokens.reduce((score, token) => score + (item.title.toLocaleLowerCase().includes(token) ? 4 : 0) + (item.text.includes(token) ? 1 : 0), 0) }))
    .filter((item) => tokens.length === 0 || tokens.every((token) => item.text.includes(token)))
    .sort((a, b) => b.score - a.score || routes.indexOf(a.route) - routes.indexOf(b.route))
    .slice(0, 12);
  state.activeSearchIndex = 0;
  renderSearchResults();
}

function renderSearchResults() {
  if (!state.searchMatches.length) {
    const empty = document.createElement("div");
    empty.className = "search-empty";
    const mark = document.createElement("span");
    mark.textContent = "∅";
    empty.append(mark, document.createTextNode(ui[state.locale].noResults));
    elements.searchResults.replaceChildren(empty);
    return;
  }
  const fragment = document.createDocumentFragment();
  state.searchMatches.forEach((item, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `search-result${index === state.activeSearchIndex ? " active" : ""}`;
    button.setAttribute("role", "option");
    button.setAttribute("aria-selected", String(index === state.activeSearchIndex));
    button.addEventListener("click", () => openSearchResult(index));
    const mark = document.createElement("span");
    mark.textContent = String(routes.indexOf(item.route) + 1).padStart(2, "0");
    const copy = document.createElement("div");
    const title = document.createElement("b");
    title.textContent = item.title;
    const group = document.createElement("small");
    group.textContent = item.group;
    copy.append(title, group);
    button.append(mark, copy);
    fragment.append(button);
  });
  elements.searchResults.replaceChildren(fragment);
}

function openSearch() {
  if (!elements.searchDialog.open) elements.searchDialog.showModal();
  runSearch(elements.searchInput.value);
  requestAnimationFrame(() => elements.searchInput.focus());
}

function closeSearch() {
  if (elements.searchDialog.open) elements.searchDialog.close();
}

function openSearchResult(index) {
  const item = state.searchMatches[index];
  if (!item) return;
  closeSearch();
  location.hash = `#/${item.route}`;
}

function moveSearchSelection(direction) {
  if (!state.searchMatches.length) return;
  state.activeSearchIndex = (state.activeSearchIndex + direction + state.searchMatches.length) % state.searchMatches.length;
  renderSearchResults();
  elements.searchResults.querySelector(".active")?.scrollIntoView({ block: "nearest" });
}

function openMobileNav() {
  document.body.classList.add("nav-open");
  elements.navToggle.setAttribute("aria-expanded", "true");
  elements.navToggle.setAttribute("aria-label", ui[state.locale].closeNav);
}

function closeMobileNav() {
  document.body.classList.remove("nav-open");
  elements.navToggle.setAttribute("aria-expanded", "false");
  elements.navToggle.setAttribute("aria-label", ui[state.locale].openNav);
}

function handleRoute() {
  const parsed = parseHash();
  state.route = parsed.route;
  if (!location.hash || !pages[location.hash.replace(/^#\/?/, "").split("?")[0]]) {
    history.replaceState(null, "", `#/${parsed.route}`);
  }
  renderPage(parsed.section);
}

document.querySelectorAll("[data-locale]").forEach((button) => {
  button.addEventListener("click", () => setLocale(button.dataset.locale));
});
elements.searchTrigger.addEventListener("click", openSearch);
elements.mobileSearch.addEventListener("click", openSearch);
elements.searchClose.addEventListener("click", closeSearch);
elements.searchInput.addEventListener("input", () => runSearch(elements.searchInput.value));
elements.searchInput.addEventListener("keydown", (event) => {
  if (event.key === "ArrowDown") { event.preventDefault(); moveSearchSelection(1); }
  if (event.key === "ArrowUp") { event.preventDefault(); moveSearchSelection(-1); }
  if (event.key === "Enter") { event.preventDefault(); openSearchResult(state.activeSearchIndex); }
});
elements.searchDialog.addEventListener("click", (event) => {
  if (event.target === elements.searchDialog) closeSearch();
});
elements.navToggle.addEventListener("click", () => document.body.classList.contains("nav-open") ? closeMobileNav() : openMobileNav());
elements.navScrim.addEventListener("click", closeMobileNav);
window.addEventListener("hashchange", handleRoute);
window.addEventListener("keydown", (event) => {
  const target = event.target;
  const isTyping = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target?.isContentEditable;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    openSearch();
  } else if (event.key === "/" && !isTyping && !elements.searchDialog.open) {
    event.preventDefault();
    openSearch();
  } else if (event.key === "Escape" && document.body.classList.contains("nav-open")) {
    closeMobileNav();
  }
});

setLocale(state.locale);
loadAllPages().then(() => {
  handleRoute();
}).catch((error) => {
  elements.article.innerHTML = `<p class="lead">Failed to load documentation: ${escapeHtml(error.message)}</p>`;
  console.error(error);
});
