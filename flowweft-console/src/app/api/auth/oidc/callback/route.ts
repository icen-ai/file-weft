import { NextResponse, type NextRequest } from "next/server";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";

export async function GET(request: NextRequest): Promise<NextResponse> {
  const runtime = getConsoleAuthRuntime();
  const stateValues = request.nextUrl.searchParams.getAll("state");
  if (stateValues.length !== 1 || !stateValues[0] || request.nextUrl.search.length > 8_192) {
    return failedRedirect(runtime.config.publicOrigin, "zh");
  }
  const state = stateValues[0];
  const errorValues = request.nextUrl.searchParams.getAll("error");
  if (errorValues.length > 0) {
    let returnPath: string | null = null;
    try {
      returnPath = await runtime.oidc.abandon(state, Date.now());
    } catch {
      // The public response intentionally does not distinguish an expired or malformed state.
    }
    return failedRedirect(runtime.config.publicOrigin, localeFromReturnPath(returnPath));
  }
  const codeValues = request.nextUrl.searchParams.getAll("code");
  if (codeValues.length !== 1 || !codeValues[0]) {
    return failedRedirect(runtime.config.publicOrigin, "zh");
  }

  try {
    const completion = await runtime.oidc.complete(state, codeValues[0], Date.now());
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
  } catch {
    return failedRedirect(runtime.config.publicOrigin, "zh");
  }
}

function failedRedirect(publicOrigin: string | null, locale: "zh" | "en"): NextResponse {
  if (!publicOrigin) {
    const response = NextResponse.json(
      { code: "UNAVAILABLE", message: "Authentication is unavailable." },
      { status: 503 },
    );
    applyNoStoreHeaders(response);
    return response;
  }
  const response = NextResponse.redirect(new URL(`/${locale}/login?auth=failed`, publicOrigin), 303);
  applyNoStoreHeaders(response);
  return response;
}

function localeFromReturnPath(path: string | null): "zh" | "en" {
  return path?.startsWith("/en") ? "en" : "zh";
}

function requirePublicOrigin(value: string | null): string {
  if (!value) {
    throw new Error("Console public origin is not configured.");
  }
  return value;
}

function applyNoStoreHeaders(response: NextResponse): void {
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Content-Type-Options", "nosniff");
}
