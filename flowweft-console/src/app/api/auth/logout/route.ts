import { NextResponse, type NextRequest } from "next/server";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { revokeConsoleSession } from "@/server/auth/ConsoleSessionAccess";
import { requireSameOriginMutation } from "@/server/security/RequestSecurity";

export async function POST(request: NextRequest): Promise<NextResponse> {
  const runtime = getConsoleAuthRuntime();
  try {
    requireSameOriginMutation(request, runtime.config.publicOrigin);
  } catch {
    return NextResponse.json(
      { code: "INVALID_REQUEST", message: "Logout request was rejected." },
      { status: 400, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } },
    );
  }
  try {
    await revokeConsoleSession(request.cookies.get(runtime.config.sessionCookieName)?.value);
  } catch {
    return NextResponse.json(
      { code: "UNAVAILABLE", message: "Logout could not be completed." },
      { status: 503, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } },
    );
  }
  const loginUrl = runtime.config.publicOrigin
    ? new URL("/zh/login", runtime.config.publicOrigin)
    : new URL("/zh/login", request.nextUrl.origin);
  const response = NextResponse.redirect(loginUrl, 303);
  response.cookies.set(runtime.config.sessionCookieName, "", {
    httpOnly: true,
    secure: runtime.config.secureCookies,
    sameSite: "lax",
    path: "/",
    maxAge: 0,
    priority: "high",
  });
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Clear-Site-Data", '"cache"');
  response.headers.set("X-Content-Type-Options", "nosniff");
  return response;
}
