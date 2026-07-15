import { notFound } from "next/navigation";
import { ApprovalInbox } from "@/features/approvals/ApprovalInbox";
import { CapabilityPage } from "@/features/capabilities/CapabilityPage";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface ApprovalsPageProps {
  readonly params: Promise<{ locale: string }>;
  readonly searchParams: Promise<{ cursor?: string | string[] }>;
}

export default async function ApprovalsPage({ params, searchParams }: ApprovalsPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  const rawCursor = (await searchParams).cursor;
  if (Array.isArray(rawCursor)) {
    return <CapabilityPage id="approvals" locale={locale} />;
  }
  try {
    const page = await getConsoleDataAccess().getApprovalInboxPage({
      ...(rawCursor ? { cursor: rawCursor } : {}),
      limit: 25,
    });
    return <ApprovalInbox locale={locale} page={page} />;
  } catch {
    return <CapabilityPage id="approvals" locale={locale} />;
  }
}
