import { describe, expect, it } from "vitest";
import { buildContentSecurityPolicy } from "@/security/csp";

describe("content security policy", () => {
  it("uses a request nonce and a closed production policy", () => {
    const policy = buildContentSecurityPolicy({ nonce: "test-nonce", development: false });

    expect(policy).toContain("script-src 'self' 'nonce-test-nonce' 'strict-dynamic'");
    expect(policy).toContain("style-src 'self' 'nonce-test-nonce'");
    expect(policy).toContain("connect-src 'self'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("form-action 'self'");
    expect(policy).toContain("upgrade-insecure-requests");
    expect(policy).not.toContain("'unsafe-inline'");
    expect(policy).not.toContain("'unsafe-eval'");
  });

  it("permits React development evaluation without broadening connections", () => {
    const policy = buildContentSecurityPolicy({ nonce: "dev-nonce", development: true });

    expect(policy).toContain("'unsafe-eval'");
    expect(policy).toContain("connect-src 'self'");
    expect(policy).not.toContain("upgrade-insecure-requests");
  });
});
