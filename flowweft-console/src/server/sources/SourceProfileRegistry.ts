import "server-only";
import type { SourceProfileSummary } from "@/contracts/bff";
import type {
  ConsoleServerConfig,
  ConsoleSourceProfileDefinition,
} from "@/server/config/schema";
import { requireAllowedSourceProfile } from "@/server/sources/SourceProfilePolicy";

export class SourceProfileNotConfiguredError extends Error {
  constructor() {
    super("The selected source profile has no server-side definition.");
    this.name = "SourceProfileNotConfiguredError";
  }
}

export interface SourceProfileRegistry {
  listSummaries(): readonly SourceProfileSummary[];
  requireDefinition(candidate: unknown): ConsoleSourceProfileDefinition;
}

export function createSourceProfileRegistry(config: ConsoleServerConfig): SourceProfileRegistry {
  const definitions = new Map(config.sourceProfiles.map((profile) => [profile.id, profile]));
  const summaries = config.allowedSourceProfileIds.map((id): SourceProfileSummary => {
    const definition = definitions.get(id);
    const availableAuthenticationModes = definition && config.publicOrigin
      ? definition.authenticationModes.filter((mode) =>
        mode === "OIDC_PKCE" ? Boolean(definition.oidc) : Boolean(definition.hostTokenExchange))
      : [];
    return Object.freeze({
      id,
      displayName: definition?.displayName ?? id,
      authenticationModes: Object.freeze(availableAuthenticationModes),
      // A profile becomes ready only when at least one advertised authentication path is fully configured.
      state: definition ? availableAuthenticationModes.length > 0 ? "AVAILABLE" : "DEGRADED" : "UNAVAILABLE",
    });
  });

  return Object.freeze({
    listSummaries: () => summaries,
    requireDefinition(candidate: unknown): ConsoleSourceProfileDefinition {
      const selection = requireAllowedSourceProfile(candidate, config.allowedSourceProfileIds);
      const definition = definitions.get(selection.id);
      if (!definition) {
        throw new SourceProfileNotConfiguredError();
      }
      return definition;
    },
  });
}
