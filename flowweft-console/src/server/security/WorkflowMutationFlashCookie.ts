export const WORKFLOW_MUTATION_FLASH_TTL_SECONDS = 120;

const DEFAULT_SESSION_COOKIE_NAME = "__Host-flowweft_session";
const FLASH_COOKIE_SUFFIX = "_workflow_flash";

export function workflowMutationFlashCookieName(
  sessionCookieName = DEFAULT_SESSION_COOKIE_NAME,
): string {
  if (!/^[_A-Za-z0-9-]{1,80}$/u.test(sessionCookieName)) {
    throw new Error("Workflow mutation flash cookie name is invalid.");
  }
  return `${sessionCookieName}${FLASH_COOKIE_SUFFIX}`;
}
