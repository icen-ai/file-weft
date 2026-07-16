// @vitest-environment node

import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/server/security/PinnedJsonHttpClient", () => ({
  requestPinnedJson: vi.fn(),
}));

import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import {
  FlowWeftBackendClientError,
  readApprovalInboxPage,
  readDocumentDetail,
  readDocumentPage,
  readSystemDoctorReport,
} from "@/server/dal/FlowWeftBackendClient";
import { parseConsoleServerConfig } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const requestPinnedJsonMock = vi.mocked(requestPinnedJson);

describe("explicit FlowWeft Console document DAL", () => {
  beforeEach(() => requestPinnedJsonMock.mockReset());
  it("uses the server token and fixed API path while returning only a validated document projection", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    const session = storedSession();
    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: {
        items: [{
          id: "doc-1",
          documentNumber: "FW-001",
          title: "Knowledge policy",
          lifecycleState: "PUBLISHED",
          createdTime: 1_700_000_000_000,
          updatedTime: 1_700_000_100_000,
          currentVersionId: "version-1",
          folderId: null,
        }],
        nextCursor: "opaque-cursor",
        total: 7,
      },
      error: null,
      traceId: "trace-1",
    });

    const page = await readDocumentPage(profile, session, {
      limit: 25,
      lifecycleState: "PUBLISHED",
    });
    expect(page.items[0]?.title).toBe("Knowledge policy");
    expect(Object.isFrozen(page.items)).toBe(true);
    const request = requestPinnedJsonMock.mock.calls[0]?.[0];
    expect(request).toMatchObject({
      url: "https://flowweft.example/fileweft/v1/documents",
      method: "GET",
      query: { limit: "25", lifecycleState: "PUBLISHED" },
      allowPrivateNetwork: true,
    });
    expect(request?.headers).toEqual({ Authorization: "Bearer server-only-token" });
    expect(JSON.stringify(page)).not.toContain("server-only-token");
  });

  it("fails closed for profile/session confusion and inconsistent upstream pages before projection", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    await expect(readDocumentPage(profile, { ...storedSession(), sourceProfileId: "other" }))
      .rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();

    await expect(readDocumentPage(profile, {
      ...storedSession(),
      sourceProfileBindingDigest: "C".repeat(43),
    })).rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();

    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: { items: [], nextCursor: null, total: -1 },
      error: null,
      traceId: null,
    });
    await expect(readDocumentPage(profile, storedSession()))
      .rejects.toBeInstanceOf(FlowWeftBackendClientError);
  });

  it("reads an exact document detail and rejects a broken current-version chain", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    const detail = {
      document: {
        id: "document:legal-1",
        documentNumber: "FW-LEGAL-1",
        title: "Retention policy",
        lifecycleState: "PUBLISHED",
        createdTime: 1_700_000_000_000,
        updatedTime: 1_700_000_100_000,
        currentVersionId: "version-2",
        folderId: "legal",
      },
      versions: [{
        id: "version-2",
        versionNumber: "2.0",
        fileName: "retention-policy.pdf",
        contentLength: 2_048,
        createdTime: 1_700_000_100_000,
        updatedTime: 1_700_000_100_000,
        contentType: "application/pdf",
      }],
    };
    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: detail,
      error: null,
      traceId: "trace-detail",
    });

    const result = await readDocumentDetail(profile, storedSession(), "document:legal-1");

    expect(result.versions[0]?.fileName).toBe("retention-policy.pdf");
    expect(Object.isFrozen(result.document)).toBe(true);
    expect(Object.isFrozen(result.versions)).toBe(true);
    expect(requestPinnedJsonMock.mock.calls[0]?.[0]).toMatchObject({
      url: "https://flowweft.example/fileweft/v1/documents/document%3Alegal-1",
      method: "GET",
      headers: { Authorization: "Bearer server-only-token" },
    });

    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: { ...detail, document: { ...detail.document, currentVersionId: "missing-version" } },
      error: null,
    });
    await expect(readDocumentDetail(profile, storedSession(), "document:legal-1"))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
    await expect(readDocumentDetail(profile, storedSession(), "../other-tenant"))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
  });

  it("reads only the redacted system Doctor contract and rejects inconsistent aggregate status", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: {
        status: "WARNING",
        checks: [{
          checkerName: "storage",
          status: "WARNING",
          reason: "Storage check requires attention.",
          repairSuggestion: "Review authorized operational logs.",
        }],
        inspectedTime: 1_700_000_200_000,
      },
      error: null,
      traceId: null,
    });

    const report = await readSystemDoctorReport(profile, storedSession());
    expect(report.status).toBe("WARNING");
    expect(Object.isFrozen(report.checks)).toBe(true);
    expect(requestPinnedJsonMock.mock.calls[0]?.[0]).toMatchObject({
      url: "https://flowweft.example/fileweft/v1/doctor",
      method: "GET",
      headers: { Authorization: "Bearer server-only-token" },
      allowPrivateNetwork: true,
    });
    expect(JSON.stringify(report)).not.toContain("server-only-token");

    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: {
        status: "HEALTHY",
        checks: [{
          checkerName: "storage",
          status: "ERROR",
          reason: "Storage check failed.",
          repairSuggestion: null,
        }],
        inspectedTime: 1_700_000_200_000,
      },
      error: null,
      traceId: null,
    });
    await expect(readSystemDoctorReport(profile, storedSession()))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("reads the current principal approval inbox without assignee identities or comments", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: {
        items: [{
          task: {
            id: "task-1",
            workflowId: "workflow-1",
            state: "PENDING",
            createdTime: 1_700_000_000_000,
            updatedTime: 1_700_000_100_000,
            assignedToCurrentUser: true,
          },
          document: {
            id: "doc-1",
            documentNumber: "FW-001",
            title: "Knowledge policy",
            lifecycleState: "PENDING_REVIEW",
            createdTime: 1_699_000_000_000,
            updatedTime: 1_700_000_100_000,
            currentVersionId: "version-1",
            folderId: "folder-1",
          },
          workflowType: "KNOWLEDGE_FILE",
          workflowState: "PENDING",
          actionableByCurrentUser: true,
        }],
        nextCursor: "next-task-page",
        total: null,
      },
      error: null,
      traceId: null,
    });

    const page = await readApprovalInboxPage(profile, storedSession(), { limit: 25 });
    expect(page.items[0]?.task.id).toBe("task-1");
    expect(JSON.stringify(page)).not.toMatch(/comment|assigneeId|server-only-token/u);
    expect(requestPinnedJsonMock.mock.calls[0]?.[0]).toMatchObject({
      url: "https://flowweft.example/fileweft/v1/workflows/tasks",
      method: "GET",
      query: { limit: "25" },
      headers: { Authorization: "Bearer server-only-token" },
    });

    requestPinnedJsonMock.mockResolvedValueOnce({
      code: "OK",
      message: "OK",
      data: {
        items: [{
          task: {
            id: "task-1",
            workflowId: "workflow-1",
            state: "PENDING",
            createdTime: 1,
            updatedTime: 1,
            assignedToCurrentUser: false,
          },
          document: {
            id: "doc-1",
            documentNumber: "FW-001",
            title: "Policy",
            lifecycleState: "PENDING_REVIEW",
            createdTime: 1,
            updatedTime: 1,
            currentVersionId: null,
            folderId: null,
          },
          workflowType: "REVIEW",
          workflowState: "PENDING",
          actionableByCurrentUser: false,
        }],
        nextCursor: null,
        total: 1,
      },
      error: null,
    });
    await expect(readApprovalInboxPage(profile, storedSession()))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
});

function backendConfig() {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["OIDC_PKCE"],
        api: { allowPrivateNetwork: true },
      }],
    }),
  });
}

function storedSession(): StoredConsoleSession {
  const profile = backendConfig().sourceProfiles[0]!;
  return Object.freeze({
    sessionIdDigest: "A".repeat(43),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: sourceProfileBindingDigest(profile),
    subjectId: "user-1",
    subjectDisplayName: "Alice",
    tenantAlias: "Tianjin",
    accessToken: "server-only-token",
    tokenType: "Bearer",
    createdAtEpochMillis: 1_700_000_000_000,
    expiresAtEpochMillis: 1_700_003_600_000,
  });
}
