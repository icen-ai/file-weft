// @vitest-environment node

import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  readBoundedForm,
  requireSameOriginMutation,
  requireSingleFormValue,
} from "@/server/security/RequestSecurity";

describe("Console mutation request boundary", () => {
  it("requires the exact configured origin and same-origin fetch metadata", () => {
    const accepted = request("sourceProfileId=primary", {
      origin: "https://console.example",
      "sec-fetch-site": "same-origin",
    });
    expect(() => requireSameOriginMutation(accepted, "https://console.example")).not.toThrow();

    const sibling = request("sourceProfileId=primary", {
      origin: "https://attacker.console.example",
      "sec-fetch-site": "same-site",
    });
    expect(() => requireSameOriginMutation(sibling, "https://console.example")).toThrow();
    expect(() => requireSameOriginMutation(accepted, null)).toThrow();
  });

  it("reads a bounded form and rejects duplicate authority fields", async () => {
    const parsed = await readBoundedForm(request("sourceProfileId=primary&locale=zh"));
    expect(requireSingleFormValue(parsed, "sourceProfileId", 80)).toBe("primary");
    expect(requireSingleFormValue(parsed, "locale", 2)).toBe("zh");

    const duplicate = await readBoundedForm(request("sourceProfileId=primary&sourceProfileId=other"));
    expect(() => requireSingleFormValue(duplicate, "sourceProfileId", 80)).toThrow();
  });

  it("rejects the wrong media type and streaming body overflow", async () => {
    const json = new NextRequest("https://console.example/api/auth/oidc/start", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    });
    await expect(readBoundedForm(json)).rejects.toThrow();
    await expect(readBoundedForm(request("x=" + "a".repeat(100)), 32)).rejects.toThrow();
  });
});

function request(body: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest("https://console.example/api/auth/oidc/start", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      ...headers,
    },
    body,
  });
}
