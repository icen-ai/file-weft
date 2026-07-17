import { NextResponse, type NextRequest } from "next/server";
import { isLocale } from "@/i18n/locale";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { ConsoleAuthStoreCapacityError } from "@/server/auth/ConsoleAuthStore";
import { OidcLoginError } from "@/server/auth/OidcLoginService";
import { ConsoleAuthStoreUnavailableError } from "@/server/auth/RedisConsoleAuthStore";
import { SourceProfileNotConfiguredError } from "@/server/sources/SourceProfileRegistry";
import {
  readBoundedForm,
  requireSameOriginMutation,
  requireSingleFormValue,
} from "@/server/security/RequestSecurity";

export async function POST(request: NextRequest): Promise<NextResponse> {
  try {
    const runtime = getConsoleAuthRuntime();
    requireSameOriginMutation(request, runtime.config.publicOrigin);
    const form = await readBoundedForm(request);
    const sourceProfileId = requireSingleFormValue(form, "sourceProfileId", 80);
    const locale = requireSingleFormValue(form, "locale", 2);
    if (!isLocale(locale)) {
      throw new Error("Unsupported locale.");
    }
    const profile = runtime.sources.requireDefinition(sourceProfileId);
    const authorization = await runtime.oidc.begin(profile, `/${locale}`, Date.now());
    const response = NextResponse.redirect(authorization.location, 303);
    applyNoStoreHeaders(response);
    return response;
  } catch (error) {
    if (error instanceof ConsoleAuthStoreCapacityError ||
      error instanceof ConsoleAuthStoreUnavailableError ||
      error instanceof SourceProfileNotConfiguredError ||
      error instanceof OidcLoginError && error.code === "NOT_CONFIGURED") {
      return problem(503, "UNAVAILABLE");
    }
    return problem(400, "INVALID_REQUEST");
  }
}

function problem(status: number, code: string): NextResponse {
  const response = NextResponse.json({ code, message: "Authentication request was rejected." }, { status });
  applyNoStoreHeaders(response);
  return response;
}

function applyNoStoreHeaders(response: NextResponse): void {
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Content-Type-Options", "nosniff");
}
