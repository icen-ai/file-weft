import { NextResponse, type NextRequest } from "next/server";
import { isLocale } from "@/i18n/locale";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { ConsoleAuthStoreCapacityError } from "@/server/auth/ConsoleAuthStore";
import { HostTokenExchangeError } from "@/server/auth/HostTokenExchangeService";
import { ConsoleLoginRateLimitedError } from "@/server/auth/LoginAttemptLimiter";
import { ConsoleAuthStoreUnavailableError } from "@/server/auth/RedisConsoleAuthStore";
import { SourceProfileNotConfiguredError } from "@/server/sources/SourceProfileRegistry";
import {
  readBoundedForm,
  requireSameOriginMutation,
  requireSingleFormValue,
} from "@/server/security/RequestSecurity";

export async function POST(request: NextRequest): Promise<NextResponse> {
  const runtime = getConsoleAuthRuntime();
  let locale: "zh" | "en" = "zh";
  try {
    requireSameOriginMutation(request, runtime.config.publicOrigin);
    const form = await readBoundedForm(request, 16_384);
    const candidateLocale = requireSingleFormValue(form, "locale", 2);
    if (!isLocale(candidateLocale)) {
      throw new HostTokenExchangeError("INVALID_REQUEST");
    }
    locale = candidateLocale;
    const profile = runtime.sources.requireDefinition(
      requireSingleFormValue(form, "sourceProfileId", 80),
    );
    const completion = await runtime.hostTokenExchange.exchange(profile, {
      tenantAlias: requireSingleFormValue(form, "tenantAlias", 256),
      username: requireSingleFormValue(form, "username", 256),
      password: requireSingleFormValue(form, "password", 4_096),
    }, `/${locale}`, Date.now());
    const response = NextResponse.redirect(
      new URL(completion.returnPath, requirePublicOrigin(runtime.config.publicOrigin)),
      303,
    );
    const maxAge = Math.max(1, Math.floor((Date.parse(completion.session.expiresAt) - Date.now()) / 1_000));
    response.cookies.set(runtime.config.sessionCookieName, completion.sessionId, {
      httpOnly: true,
      secure: runtime.config.secureCookies,
      sameSite: "lax",
      path: "/",
      maxAge,
      priority: "high",
    });
    applyNoStoreHeaders(response);
    return response;
  } catch (error) {
    if (error instanceof ConsoleLoginRateLimitedError) {
      return failedRedirect(runtime.config.publicOrigin, locale, "limited");
    }
    if (error instanceof HostTokenExchangeError &&
      (error.code === "REJECTED" || error.code === "INVALID_IDENTITY")) {
      return failedRedirect(runtime.config.publicOrigin, locale, "failed");
    }
    if (error instanceof ConsoleAuthStoreCapacityError ||
      error instanceof ConsoleAuthStoreUnavailableError ||
      error instanceof SourceProfileNotConfiguredError ||
      error instanceof HostTokenExchangeError && error.code === "NOT_CONFIGURED") {
      return problem(503, "UNAVAILABLE");
    }
    return problem(400, "INVALID_REQUEST");
  }
}

function failedRedirect(
  publicOrigin: string | null,
  locale: "zh" | "en",
  state: "failed" | "limited",
): NextResponse {
  if (!publicOrigin) {
    return problem(503, "UNAVAILABLE");
  }
  const response = NextResponse.redirect(new URL(`/${locale}/login?auth=${state}`, publicOrigin), 303);
  applyNoStoreHeaders(response);
  return response;
}

function problem(status: number, code: string): NextResponse {
  const response = NextResponse.json({ code, message: "Authentication request was rejected." }, { status });
  applyNoStoreHeaders(response);
  return response;
}

function requirePublicOrigin(value: string | null): string {
  if (!value) {
    throw new HostTokenExchangeError("NOT_CONFIGURED");
  }
  return value;
}

function applyNoStoreHeaders(response: NextResponse): void {
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Content-Type-Options", "nosniff");
}
