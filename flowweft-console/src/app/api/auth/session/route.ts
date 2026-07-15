import { NextResponse, type NextRequest } from "next/server";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { readConsoleSession } from "@/server/auth/ConsoleSessionAccess";

export async function GET(request: NextRequest): Promise<NextResponse> {
  const config = getConsoleAuthRuntime().config;
  let session;
  try {
    session = await readConsoleSession(request.cookies.get(config.sessionCookieName)?.value);
  } catch {
    const unavailable = NextResponse.json(
      { code: "UNAVAILABLE", message: "The session service is unavailable." },
      { status: 503 },
    );
    applyNoStoreHeaders(unavailable);
    return unavailable;
  }
  const response = session
    ? NextResponse.json(session, { status: 200 })
    : NextResponse.json({ code: "UNAUTHENTICATED", message: "Authentication is required." }, { status: 401 });
  applyNoStoreHeaders(response);
  return response;
}

function applyNoStoreHeaders(response: NextResponse): void {
  response.headers.set("Cache-Control", "private, no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("X-Content-Type-Options", "nosniff");
}
