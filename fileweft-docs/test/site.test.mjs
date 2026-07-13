import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { groups, orderedRoutes, pages, ui } from "../content.js";

const root = new URL("../", import.meta.url);
const read = (name) => readFile(new URL(name, root), "utf8");
const hostIntegrationPages = [
  "../README.md",
  "pages/zh/getting-started/quickstart.md",
  "pages/en/getting-started/quickstart.md",
  "pages/zh/getting-started/installation.md",
  "pages/en/getting-started/installation.md",
  "pages/zh/guides/spring-boot.md",
  "pages/en/guides/spring-boot.md",
  "pages/zh/getting-started/first-integration.md",
  "pages/en/getting-started/first-integration.md",
];
const publicHttpApiPages = [
  "pages/zh/reference/http-api.md",
  "pages/en/reference/http-api.md",
];
const releaseScopePages = [
  "pages/en/project/release-0-0-2-development.md",
  "pages/zh/project/release-0-0-2-development.md",
  "pages/en/project/roadmap.md",
  "pages/zh/project/roadmap.md",
  "../docs/implementation-status.md",
  "../docs/releases/0.0.2.md",
];
const releaseIdentityPages = [
  "../README.md",
  "../docs/implementation-status.md",
  "../docs/public-web-api-v1.md",
  "../docs/releases/0.0.2.md",
  "pages/en/getting-started/installation.md",
  "pages/zh/getting-started/installation.md",
  "pages/en/project/release-0-0-2-development.md",
  "pages/zh/project/release-0-0-2-development.md",
  "pages/en/project/faq.md",
  "pages/zh/project/faq.md",
];
const migrationBoundaryPages = [
  "pages/en/operations/migrations-release.md",
  "pages/zh/operations/migrations-release.md",
  "pages/en/project/release-0-0-2-development.md",
  "pages/zh/project/release-0-0-2-development.md",
  "../README.md",
  "../docs/implementation-status.md",
  "../docs/production-operations.md",
  "../docs/releases/0.0.2.md",
];

const parseFrontmatter = (source) => {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  assert.ok(match, "markdown source must contain complete frontmatter");
  return Object.fromEntries(match[1].split(/\r?\n/).map((line) => {
    const separator = line.indexOf(":");
    assert.ok(separator > 0, `invalid frontmatter line: ${line}`);
    const key = line.slice(0, separator).trim();
    const rawValue = line.slice(separator + 1).trim();
    return [key, rawValue.startsWith('"') ? JSON.parse(rawValue) : rawValue];
  }));
};

test("every navigation group has ordered bilingual pages with markdown sources", async () => {
  const routes = orderedRoutes();
  assert.ok(routes.length >= 18, "documentation should cover the full product surface");
  assert.equal(new Set(routes).size, routes.length);
  for (const group of groups) {
    assert.ok(routes.some((route) => pages[route].group === group.id), `missing pages for ${group.id}`);
    assert.ok(group.en && group.zh);
  }
  for (const route of routes) {
    const entry = pages[route];
    assert.match(route, /^[a-z0-9-]+\/[a-z0-9-]+$/);
    assert.ok(groups.some((group) => group.id === entry.group), `unknown group for ${route}`);
    for (const locale of ["en", "zh"]) {
      const meta = entry[locale];
      assert.ok(meta.nav.length > 1, `${route} ${locale} navigation label`);
      assert.ok(meta.title.length > 3, `${route} ${locale} title`);
      assert.ok(meta.lead.length > 20, `${route} ${locale} lead`);
      assert.ok(meta.file.endsWith(".md") && meta.file.includes(`/${locale}/`), `${route} ${locale} file path`);
      const source = await read(meta.file);
      const frontmatter = parseFrontmatter(source);
      assert.equal(frontmatter.route, route, `${route} ${locale} frontmatter route`);
      assert.equal(frontmatter.locale, locale, `${route} ${locale} frontmatter locale`);
      assert.equal(frontmatter.nav, meta.nav, `${route} ${locale} frontmatter nav`);
      assert.equal(frontmatter.title, meta.title, `${route} ${locale} frontmatter title`);
      assert.equal(frontmatter.lead, meta.lead, `${route} ${locale} frontmatter lead`);
      assert.doesNotMatch(source, /<script\b/i, `${route} must not embed scripts`);
      assert.doesNotMatch(source, /\son\w+\s*=/i, `${route} must not embed event handlers`);
      const markdownHeadings = source.match(/^## /gm) || [];
      const htmlHeadings = source.match(/<h2[\s>]/gim) || [];
      assert.ok(markdownHeadings.length + htmlHeadings.length >= 2, `${route} ${locale} must have at least two sections`);
    }
  }
});

test("site shell uses local assets and exposes accessible controls", async () => {
  const html = await read("index.html");
  assert.match(html, /<html lang="en">/);
  assert.match(html, /href="#doc-content"/);
  assert.match(html, /id="primary-nav"/);
  assert.match(html, /id="search-dialog"[^>]*aria-labelledby=/);
  assert.match(html, /id="doc-content"[^>]*tabindex="-1"/);
  assert.match(html, /data-locale="en"[^>]*aria-pressed="true"/);
  assert.match(html, /data-locale="zh"[^>]*aria-pressed="false"/);
  const externalAsset = /(?:src|href)="https?:\/\//gi;
  assert.doesNotMatch(html, externalAsset, "runtime assets must not use a CDN");
  assert.doesNotMatch(html, /javascript:/i);
});

test("interaction script includes hash routing, search shortcuts and copy support", async () => {
  const source = await read("app.js");
  assert.match(source, /addEventListener\("hashchange"/);
  assert.match(source, /navigator\.clipboard\.writeText/);
  assert.match(source, /event\.key\.toLowerCase\(\) === "k"/);
  assert.match(source, /event\.key === "\/"/);
  assert.match(source, /aria-current/);
});

test("visual system preserves FileWeft tokens and responsive behavior", async () => {
  const css = await read("styles.css");
  for (const token of ["--ink: #101613", "--paper: #f8f8f1", "--acid: #c8ed3c", "Songti SC"]) {
    assert.ok(css.includes(token), `missing visual token ${token}`);
  }
  assert.match(css, /@media \(max-width: 760px\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css, /:focus-visible/);
  assert.doesNotMatch(css, /@import\s+url/i, "fonts and styles must remain local");
});

test("all interface strings exist in both languages", () => {
  assert.deepEqual(Object.keys(ui.en).sort(), Object.keys(ui.zh).sort());
  for (const locale of ["en", "zh"]) {
    for (const [key, value] of Object.entries(ui[locale])) {
      assert.ok(value.trim(), `${locale}.${key} must not be blank`);
    }
  }
});

test("public HTTP errors expose only stable code and safe message fields", async () => {
  const apiErrorSource = await read(
    "../fileweft-web-api/src/main/kotlin/ai/icen/fw/web/api/ApiError.kt",
  );
  assert.match(apiErrorSource, /val code: String/u);
  assert.match(apiErrorSource, /val message: String/u);
  assert.doesNotMatch(apiErrorSource, /\bdetails\b/u);

  for (const page of publicHttpApiPages) {
    const source = await read(page);
    assert.doesNotMatch(source, /`details`/u, `${page} must not advertise arbitrary error details`);
    assert.match(source, /`code`[^\n]*`message`/u, `${page} must document the fixed error fields`);
  }
});

test("every recommended Spring Boot host dependency block includes host-owned JDBC", async () => {
  for (const page of hostIntegrationPages) {
    const source = await read(page);
    const dependencyBlocks = [...source.matchAll(/```(?:kotlin|xml)\r?\n([\s\S]*?)```/g)]
      .map((match) => match[1])
      .filter((block) => /fileweft-spring-boot[23]-starter/.test(block));
    assert.ok(dependencyBlocks.length > 0, `${page} must contain a runnable host dependency block`);
    for (const block of dependencyBlocks) {
      assert.match(
        block,
        /org\.springframework\.boot(?::|<\/groupId>\s*\r?\n\s*<artifactId>)spring-boot-starter-jdbc/,
        `${page} must keep the host JDBC starter beside the FileWeft runtime starter`,
      );
    }
  }
});

test("every Boot 2 host guide pins FileWeft's Kotlin runtime contract", async () => {
  for (const page of hostIntegrationPages) {
    const source = await read(page);
    assert.match(source, /1\.6\.21/, `${page} must explain the Boot 2 BOM default`);
    assert.match(
      source,
      /extra\["kotlin\.version"\]\s*=\s*"2\.1\.21"/,
      `${page} must show the Spring Dependency Management override`,
    );
    assert.match(
      source,
      /<kotlin\.version>2\.1\.21<\/kotlin\.version>/,
      `${page} must show the Maven property override`,
    );
    assert.match(
      source,
      /org\.jetbrains\.kotlin:kotlin-bom:2\.1\.21/,
      `${page} must show the native Gradle platform alignment`,
    );
    assert.match(source, /dependencyInsight/, `${page} must require final dependency verification`);
  }
});

test("0.0.2 scope defers catalog HTTP and Agent without a promised version", async () => {
  for (const page of releaseScopePages) {
    const source = await read(page);
    assert.match(source, /(?:catalog|目录)/iu, `${page} must name the catalog boundary`);
    assert.match(
      source,
      /(?:outside|out of|removed from|not a 0\.0\.2 deliverable|移出|不是 0\.0\.2 交付项)/iu,
      `${page} must keep formal catalog HTTP outside 0.0.2`,
    );
    assert.match(
      source,
      /(?:no committed (?:target )?version|没有承诺(?:目标)?版本)/iu,
      `${page} must not promise a catalog delivery version`,
    );
    assert.match(source, /Agent/u, `${page} must carry the Agent product decision`);
    assert.match(source, /1\.0\.0/u, `${page} must defer Agent reassessment until after 1.0.0`);
    assert.match(
      source,
      /(?:not a commitment|no promised delivery version|不承诺|不构成[^。\n]*承诺)/iu,
      `${page} must make clear that Agent reassessment is not a delivery promise`,
    );
    assert.doesNotMatch(
      source,
      /(?:resumable(?:-upload)?\s+(?:and|&)\s+catalog|断点续传(?:与|和)目录)[^\n]{0,80}(?:HTTP|资源)/iu,
      `${page} must not combine catalog HTTP with the 0.0.2 resumable-upload commitment`,
    );
  }
});

test("0.0.2 release identity is conditional on protected remote evidence without stale development status", async () => {
  for (const page of releaseIdentityPages) {
    const source = await read(page);
    assert.match(source, /0\.0\.2/u, `${page} release identity`);
    assert.match(source, /(?:protected[^\n]{0,40}tag|受保护[^\n]{0,40}标签)/iu, `${page} protected tag evidence`);
    assert.match(
      source,
      /(?:anonymous[^\n]{0,50}(?:cold-cache|cold cache|resolution)|匿名[^\n]{0,50}(?:冷缓存|解析))/iu,
      `${page} anonymous remote evidence`,
    );
    assert.doesNotMatch(
      source,
      /(?:current stable|stable (?:published )?version remains|当前稳定(?:版|版本)?(?:仍是|仍为|是)|稳定正式版仍是)[^\n]{0,80}0\.0\.1/iu,
      `${page} must not advertise 0.0.1 as current`,
    );
    assert.doesNotMatch(
      source,
      /0\.0\.2-SNAPSHOT[^\n]{0,80}(?:unreleased|under development|not a release|尚未发布|正在开发|不是正式版)/iu,
      `${page} must not advertise 0.0.2 as an unreleased development line`,
    );
  }
});

test("migration documentation pins the complete V001-V028 database contract", async () => {
  for (const page of migrationBoundaryPages) {
    const source = await read(page);
    assert.match(source, /28[^\n]{0,40}V001–V028|V001–V028[^\n]{0,40}28/u, `${page} migration count`);
    assert.doesNotMatch(source, /V001(?:–|-)V02[67]/u, `${page} must not publish a stale range`);
    assert.match(source, /PostgreSQL V001–V025/u, `${page} historical 0.0.1 immutability boundary`);
    assert.match(
      source,
      /(?:V001–V028[^\n]{0,180}(?:immutable|不可改写)|(?:immutable|不可改写)[^\n]{0,180}V001–V028)/iu,
      `${page} 0.0.2 full immutability boundary`,
    );
    assert.match(source, /8\.0\.17/u, `${page} MySQL lower bound`);
    assert.doesNotMatch(source, /8\.0\.16/u, `${page} must not publish the obsolete MySQL lower bound`);
    assert.match(source, /8\.0\.46/u, `${page} MySQL evidence version`);
    assert.match(source, /MariaDB/u, `${page} native MySQL boundary`);
    assert.match(source, /V027/u, `${page} worker claim-order migration`);
    assert.match(source, /V028/u, `${page} NO PAD exact-text migration`);
    assert.match(source, /utf8mb4_0900_bin/u, `${page} exact MySQL identifier comparison`);
    assert.match(source, /NO PAD/u, `${page} trailing-space comparison contract`);
    assert.match(source, /(?:trailing spaces|尾空格)/u, `${page} must preserve trailing-space identity`);
  }
});

test("migration operations document the Flyway and Kingbase host boundary", async () => {
  for (const page of [
    "pages/en/operations/migrations-release.md",
    "pages/zh/operations/migrations-release.md",
    "pages/en/reference/configuration.md",
    "pages/zh/reference/configuration.md",
    "../docs/production-operations.md",
    "../docs/releases/0.0.2.md",
  ]) {
    const source = await read(page);
    for (const version of ["8.5.13", "9.22.3", "11.7.2"]) {
      assert.match(source, new RegExp(version.replaceAll(".", "\\."), "u"), `${page} Flyway ${version}`);
    }
    assert.match(source, /flyway-core/u, `${page} Boot 3 Flyway core alignment`);
    assert.match(source, /flyway-mysql/u, `${page} Boot 3 MySQL module alignment`);
    assert.match(source, /flyway-database-postgresql/u, `${page} Boot 3 PostgreSQL module alignment`);
    assert.match(
      source,
      /fileweft\.persistence\.kingbase-flyway-compatibility-enabled/u,
      `${page} Kingbase Flyway wrapper switch`,
    );
  }
});
