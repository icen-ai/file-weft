import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

describe("static security and route boundaries", () => {
  it("has an explicit page file for every required product surface", () => {
    const routeRoot = join(root, "src", "app", "[locale]", "(console)");
    const requiredRoutes = [
      "sync",
      "audit",
      "tool-approvals",
      "retrieval",
      "evaluations",
      "settings",
    ];

    for (const route of requiredRoutes) {
      expect(readFileSync(join(routeRoot, route, "page.tsx"), "utf8")).toContain("buildCapabilityRoute");
    }
    const documents = readFileSync(join(routeRoot, "documents", "page.tsx"), "utf8");
    expect(documents).toContain("getDocumentPage");
    expect(documents).toContain("getDocumentDetail");
    expect(documents).toContain("DocumentWorkbench");
    const doctor = readFileSync(join(routeRoot, "doctor", "page.tsx"), "utf8");
    expect(doctor).toContain("getSystemDoctorReport");
    expect(doctor).toContain("SystemDoctorWorkbench");
    const approvals = readFileSync(join(routeRoot, "approvals", "page.tsx"), "utf8");
    expect(approvals).toContain("getWorkflowTaskPage");
    expect(approvals).toContain("getWorkflowDefinitionPage");
    expect(approvals).toContain("getWorkflowHistoryPage");
    expect(approvals).toContain("getWorkflowCommentPage");
    expect(approvals).toContain("WorkflowWorkbench");
    const workflowWorkbench = readFileSync(
      join(root, "src", "features", "workflow", "WorkflowWorkbench.tsx"), "utf8",
    );
    expect(workflowWorkbench).not.toMatch(/^["']use client["'];/u);
    expect(workflowWorkbench).not.toMatch(/\b(?:fetch|EventSource|WebSocket)\s*\(/u);
    const agent = readFileSync(join(routeRoot, "agent", "page.tsx"), "utf8");
    expect(agent).toContain("getAgentConversationPage");
    expect(agent).toContain("getAgentMessagePage");
    expect(agent).toContain("getAgentEventPage");
    expect(agent).toContain("getAgentCitationPage");
    expect(agent).toContain("AgentWorkbench");
    expect(agent).not.toContain("buildCapabilityRoute");
    const agentWorkbench = readFileSync(join(root, "src", "features", "agent", "AgentWorkbench.tsx"), "utf8");
    expect(agentWorkbench).not.toMatch(/^["']use client["'];/u);
    expect(agentWorkbench).not.toMatch(/\b(?:fetch|EventSource|WebSocket)\s*\(/u);
  });

  it("does not define a transparent catch-all API proxy", () => {
    const apiRoot = join(root, "src", "app", "api");
    const entries = readdirSync(apiRoot, { recursive: true }).map(String);
    expect(entries.some((entry) => entry.includes("[...") || entry.includes("[[..."))).toBe(false);
  });

  it("keeps endpoints and tokens out of browser forms while exposing the reviewed same-origin login routes", () => {
    const loginSource = readFileSync(join(root, "src", "features", "auth", "LoginFoundation.tsx"), "utf8");
    expect(loginSource).not.toMatch(/name=["'](?:endpoint|url|token)["']/i);
    expect(loginSource).toContain('action="/api/auth/oidc/start"');
    expect(loginSource).toContain('action="/api/auth/host-exchange"');
    expect(loginSource).toContain('method="post"');
    expect(loginSource).toContain('name="sourceProfileId"');
    expect(loginSource).toContain('name="locale"');
    expect(loginSource).toContain('name="password"');
    expect(loginSource).toContain('autoComplete="current-password"');
  });

  it("has no browser persistence surface for passwords, tokens, or provider secrets", () => {
    const sourceRoot = join(root, "src");
    const applicationSource = readdirSync(sourceRoot, { recursive: true })
      .map(String)
      .filter((entry) => /\.(?:ts|tsx)$/u.test(entry))
      .map((entry) => readFileSync(join(sourceRoot, entry), "utf8"))
      .join("\n");
    expect(applicationSource).not.toMatch(/\b(?:localStorage|sessionStorage|indexedDB)\b/u);
    expect(applicationSource).not.toMatch(/NEXT_PUBLIC_[A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|CREDENTIAL)/u);
  });

  it("marks DAL, runtime config, and source policy modules as server-only", () => {
    const protectedFiles = [
      join(root, "src", "server", "config", "index.ts"),
      join(root, "src", "server", "dal", "ConsoleDataAccess.ts"),
      join(root, "src", "server", "dal", "AgentWebBackendClient.ts"),
      join(root, "src", "server", "dal", "WorkflowWebBackendClient.ts"),
      join(root, "src", "server", "sources", "SourceProfilePolicy.ts"),
      join(root, "src", "server", "sources", "SourceProfileRegistry.ts"),
      join(root, "src", "server", "sources", "SourceProfileBinding.ts"),
      join(root, "src", "server", "diagnostics", "ConsoleSecurityReadiness.ts"),
      join(root, "src", "server", "auth", "ConsoleAuthRuntime.ts"),
      join(root, "src", "server", "auth", "ConsoleSessionAccess.ts"),
      join(root, "src", "server", "auth", "OidcLoginService.ts"),
      join(root, "src", "server", "auth", "HostTokenExchangeService.ts"),
      join(root, "src", "server", "auth", "LoginAttemptLimiter.ts"),
      join(root, "src", "server", "security", "ConsoleOriginBinding.ts"),
    ];

    for (const file of protectedFiles) {
      expect(readFileSync(file, "utf8")).toMatch(/^import ["']server-only["'];/);
    }
  });
});
