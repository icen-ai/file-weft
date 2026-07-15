import "server-only";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { projectSession } from "@/server/auth/OidcLoginService";
import type { ConsoleSessionProjection } from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";

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
  return await runtime.store.readSession(sha256Base64Url(sessionId), Date.now());
}

export async function revokeConsoleSession(sessionId: string | undefined): Promise<void> {
  if (!sessionId || !/^[A-Za-z0-9_-]{43}$/u.test(sessionId)) {
    return;
  }
  await getConsoleAuthRuntime().store.revokeSession(sha256Base64Url(sessionId));
}
