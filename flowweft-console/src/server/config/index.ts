import "server-only";
import { parseConsoleServerConfig, type ConsoleServerConfig } from "@/server/config/schema";

let cachedConfig: ConsoleServerConfig | undefined;

export function loadConsoleServerConfig(): ConsoleServerConfig {
  cachedConfig ??= parseConsoleServerConfig(process.env);
  return cachedConfig;
}
