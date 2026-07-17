import { NextResponse } from "next/server";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import {
  assessConsoleSecurityReadiness,
  rejectedConsoleSecurityReadiness,
  type ConsoleSecurityReadinessReport,
} from "@/server/diagnostics/ConsoleSecurityReadiness";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export async function GET() {
  let report: ConsoleSecurityReadinessReport;
  try {
    const runtime = getConsoleAuthRuntime();
    let storeAvailable = true;
    try {
      await runtime.store.checkAvailability();
    } catch {
      storeAvailable = false;
    }
    report = assessConsoleSecurityReadiness(
      runtime.config,
      runtime.sources.listSummaries(),
      storeAvailable,
    );
  } catch {
    // Configuration/secret parser failures are intentionally collapsed to a
    // stable code so the public probe cannot echo sensitive environment input.
    report = rejectedConsoleSecurityReadiness();
  }
  return NextResponse.json(report, {
    status: report.ready ? 200 : 503,
    headers: {
      "Cache-Control": "no-store, max-age=0",
      "Pragma": "no-cache",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
