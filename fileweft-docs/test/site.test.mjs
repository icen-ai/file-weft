import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { groups, orderedRoutes, pages, ui } from "../content.js";

const root = new URL("../", import.meta.url);
const read = (name) => readFile(new URL(name, root), "utf8");

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
      assert.match(source, /^---\n/);
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
