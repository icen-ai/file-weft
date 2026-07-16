import "server-only";

import { createHash } from "node:crypto";
import type { ConsoleServerConfig } from "@/server/config/schema";

/** Binds a server-side session to the exact same-origin cookie boundary. */
export function consoleOriginBindingDigest(config: ConsoleServerConfig): string {
  if (!config.publicOrigin) {
    throw new Error("Console origin is not configured.");
  }
  return createHash("sha256")
    .update(JSON.stringify([
      "flowweft-console-origin-binding-v1",
      config.publicOrigin,
      config.sessionCookieName,
      config.secureCookies,
      config.sessionTtlSeconds,
    ]), "utf8")
    .digest("base64url");
}
