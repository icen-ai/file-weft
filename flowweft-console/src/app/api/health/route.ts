import { NextResponse } from "next/server";

const healthProjection = Object.freeze({
  status: "UP",
  component: "flowweft-console",
  phase: "source-discovery",
  backendConnected: false,
});

export function GET() {
  return NextResponse.json(healthProjection, {
    headers: {
      "Cache-Control": "no-store, max-age=0",
    },
  });
}
