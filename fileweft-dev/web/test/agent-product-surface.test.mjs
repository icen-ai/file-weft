import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const [html, app, i18n] = await Promise.all([
  readFile(new URL("../index.html", import.meta.url), "utf8"),
  readFile(new URL("../app.js", import.meta.url), "utf8"),
  readFile(new URL("../i18n.js", import.meta.url), "utf8"),
]);

test("the development UI has no Agent result or confirmation surface", () => {
  ["agent-results-section", "agent-result-list", "evidence.agent"].forEach((marker) => {
    assert.ok(!html.includes(marker), `index.html still contains ${marker}`);
  });

  ["detail.agentResults", "agent:suggestion:", "data-agent-confirm", "/api/documents/agent-results"].forEach((marker) => {
    assert.ok(!app.includes(marker), `app.js still contains ${marker}`);
  });

  const publicCheckers = app.match(/const DOCTOR_PUBLIC_CHECKERS = new Set\(\[([\s\S]*?)\]\);/)?.[1];
  assert.ok(publicCheckers, "app.js must retain an explicit public Doctor checker allowlist");
  assert.ok(!/\"agent\"/.test(publicCheckers), "the Doctor checker allowlist still promotes Agent as a product surface");
});

test("Agent product copy is absent from both locale dictionaries", () => {
  [
    "doctor.checker.agent",
    "evidence.agent",
    "empty.agent",
    "action.confirmAgent",
    "agent.confirmed",
    "agent.capability.CLASSIFICATION",
    "notice.agentConfirmed",
  ].forEach((key) => {
    assert.ok(!i18n.includes(`\"${key}\"`), `i18n.js still defines ${key}`);
  });
});
