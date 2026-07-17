import "server-only";

import { createHash } from "node:crypto";
import type { ConsoleSourceProfileDefinition } from "@/server/config/schema";

/**
 * Binds credential-bearing authorization and session records to the exact
 * administrator-reviewed routing and authentication policy that created them.
 * The display name is deliberately excluded because changing presentation must
 * not sign users out. Every field capable of changing a credential destination
 * or identity interpretation is included.
 */
export function sourceProfileBindingDigest(profile: ConsoleSourceProfileDefinition): string {
  const canonicalPolicy = [
    "flowweft-console-source-profile-binding-v1",
    profile.id,
    profile.baseUrl,
    [...profile.authenticationModes].sort(),
    profile.api ? [profile.api.allowPrivateNetwork] : null,
    profile.hostTokenExchange ? [
      profile.hostTokenExchange.endpointPath,
      profile.hostTokenExchange.allowPrivateNetwork,
    ] : null,
    profile.oidc ? [
      profile.oidc.issuer,
      profile.oidc.authorizationEndpoint,
      profile.oidc.tokenEndpoint,
      profile.oidc.jwksUri,
      profile.oidc.clientId,
      [...profile.oidc.scopes].sort(),
      profile.oidc.tenantAliasClaim,
      profile.oidc.displayNameClaim,
      [...profile.oidc.allowedAlgorithms].sort(),
      profile.oidc.allowPrivateNetwork,
    ] : null,
  ] as const;
  return createHash("sha256")
    .update(JSON.stringify(canonicalPolicy), "utf8")
    .digest("base64url");
}
