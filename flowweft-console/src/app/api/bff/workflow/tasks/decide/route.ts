import type { NextRequest } from "next/server";
import { handleWorkflowMutation } from "@/server/workflow/WorkflowMutationService";

export async function POST(request: NextRequest) {
  return handleWorkflowMutation(request, "DECIDE");
}
