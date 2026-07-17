import "server-only";
import { sourceProfileIdSchema } from "@/server/config/schema";

export interface SourceProfileSelection {
  readonly id: string;
}

export function requireAllowedSourceProfile(
  candidate: unknown,
  allowedProfileIds: readonly string[],
): SourceProfileSelection {
  const id = sourceProfileIdSchema.parse(candidate);
  if (!allowedProfileIds.includes(id)) {
    throw new Error("Source profile is not in the administrator allowlist.");
  }
  return Object.freeze({ id });
}
