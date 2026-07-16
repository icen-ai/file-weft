import "server-only";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { projectSession } from "@/server/auth/OidcLoginService";
import type { ConsoleSessionProjection } from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import { consoleOriginBindingDigest } from "@/server/security/ConsoleOriginBinding";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

export async function readConsoleSession(sessionId: string | undefined): Promise<ConsoleSessionProjection | null> {
  const session = await readStoredConsoleSession(sessionId);
  return session ? projectSession(session) : null;
}

/** Server-only credential-bearing view for explicit BFF DAL calls. */
export async function readStoredConsoleSession(sessionId: string | undefined): Promise<StoredConsoleSession | null> {
  if (!sessionId || !/^[A-Za-z0-9_-]{43}$/u.test(sessionId)) {
    return null;
  }
  const runtime = getConsoleAuthRuntime();
  const sessionDigest = sha256Base64Url(sessionId);
  const session = await runtime.store.readSession(sessionDigest, Date.now());
  if (!session) {
    return null;
  }
  try {
    const currentProfile = runtime.sources.requireDefinition(session.sourceProfileId);
    if (session.consoleOriginBindingDigest === consoleOriginBindingDigest(runtime.config) &&
      session.sourceProfileBindingDigest === sourceProfileBindingDigest(currentProfile)) {
      return session;
    }
  } catch {
    // A removed profile is the same security condition as a changed profile:
    // the credential no longer has an administrator-approved destination.
  }
  await runtime.store.revokeSession(sessionDigest);
  return null;
}

export async function revokeConsoleSession(sessionId: string | undefined): Promise<void> {
  if (!sessionId || !/^[A-Za-z0-9_-]{43}$/u.test(sessionId)) {
    return;
  }
  await getConsoleAuthRuntime().store.revokeSession(sha256Base64Url(sessionId));
}
