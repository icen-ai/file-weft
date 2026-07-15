import "server-only";
import { loadConsoleServerConfig } from "@/server/config";
import { InMemoryConsoleAuthStore } from "@/server/auth/ConsoleAuthStore";
import { HostTokenExchangeService } from "@/server/auth/HostTokenExchangeService";
import { InMemoryConsoleLoginAttemptLimiter } from "@/server/auth/LoginAttemptLimiter";
import { RedisConsoleAuthStore } from "@/server/auth/RedisConsoleAuthStore";
import { OidcLoginService } from "@/server/auth/OidcLoginService";
import { createSourceProfileRegistry } from "@/server/sources/SourceProfileRegistry";

function createRuntime() {
  const config = loadConsoleServerConfig();
  const store = config.redis
    ? new RedisConsoleAuthStore(
      config.redis,
      config.maximumPendingAuthorizations,
      config.maximumSessions,
    )
    : new InMemoryConsoleAuthStore({
      maximumAuthorizations: config.maximumPendingAuthorizations,
      maximumSessions: config.maximumSessions,
    });
  const sources = createSourceProfileRegistry(config);
  const loginLimiter = new InMemoryConsoleLoginAttemptLimiter();
  return Object.freeze({
    config,
    store,
    sources,
    oidc: new OidcLoginService(config, store, sources),
    hostTokenExchange: new HostTokenExchangeService(config, store, loginLimiter),
  });
}

interface ConsoleAuthGlobalState {
  __flowweftConsoleAuthRuntime?: ReturnType<typeof createRuntime>;
}

const globalState = globalThis as typeof globalThis & ConsoleAuthGlobalState;

export function getConsoleAuthRuntime(): ReturnType<typeof createRuntime> {
  globalState.__flowweftConsoleAuthRuntime ??= createRuntime();
  return globalState.__flowweftConsoleAuthRuntime;
}
