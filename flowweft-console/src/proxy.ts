import { NextResponse, type NextRequest } from "next/server";
import { isLocale } from "@/i18n/locale";
import { buildContentSecurityPolicy } from "@/security/csp";

function requestLocale(pathname: string): "zh" | "en" {
  const segment = pathname.split("/")[1];
  return isLocale(segment) ? segment : "zh";
}

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
  response.headers.set("Content-Security-Policy", policy);
  response.headers.set("Vary", "Cookie, Accept-Language");
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
