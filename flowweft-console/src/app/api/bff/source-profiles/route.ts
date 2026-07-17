import { NextResponse } from "next/server";
import type { BffProblem } from "@/contracts/bff";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

const privateHeaders = Object.freeze({
  "Cache-Control": "private, no-store, max-age=0",
  Pragma: "no-cache",
  "X-Content-Type-Options": "nosniff",
});

export async function GET() {
  try {
    const profiles = await getConsoleDataAccess().listSourceProfiles();
    return NextResponse.json(Object.freeze({ profiles }), { headers: privateHeaders });
  } catch {
    const problem: BffProblem = Object.freeze({
      code: "UNAVAILABLE",
      message: "Source profiles are temporarily unavailable.",
    });
    return NextResponse.json(problem, { status: 503, headers: privateHeaders });
  }
}
