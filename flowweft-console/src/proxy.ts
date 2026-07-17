import { NextResponse, type NextRequest } from "next/server";
import { isLocale } from "@/i18n/locale";
import { buildContentSecurityPolicy } from "@/security/csp";
import { workflowMutationFlashCookieName } from "@/server/security/WorkflowMutationFlashCookie";

const sessionCookieName = process.env.FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME ?? "__Host-flowweft_session";
const flashCookieName = workflowMutationFlashCookieName(sessionCookieName);
const secureCookies = process.env.FLOWWEFT_CONSOLE_SECURE_COOKIES !== "false";

function requestLocale(pathname: string): "zh" | "en" {
  const segment = pathname.split("/")[1];
  return isLocale(segment) ? segment : "zh";
}

function isWorkflowApprovalsPath(pathname: string): boolean {
  return /^\/(?:zh|en)\/approvals\/?$/u.test(pathname);
}

/**
 * The Server Component reads the authenticated Workflow flash from the incoming request. Clearing
 * it on the approvals response makes it browser-once without replacing the Console-wide CSP,
 * locale propagation, cache policy, or prefetch exclusion enforced by this proxy.
 */
export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const policy = buildContentSecurityPolicy({
    nonce,
    development: process.env.NODE_ENV === "development",
  });
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("Content-Security-Policy", policy);
  requestHeaders.set("x-flowweft-locale", requestLocale(request.nextUrl.pathname));
  requestHeaders.set("x-nonce", nonce);

  const response = NextResponse.next({
    request: { headers: requestHeaders },
  });
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Content-Security-Policy", policy);
  response.headers.set("Vary", "Cookie, Accept-Language");
  if (isWorkflowApprovalsPath(request.nextUrl.pathname) && request.cookies.has(flashCookieName)) {
    response.cookies.set(flashCookieName, "", {
      httpOnly: true,
      secure: secureCookies,
      sameSite: "strict",
      path: "/",
      maxAge: 0,
    });
  }
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico|icon.svg|manifest.webmanifest).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
