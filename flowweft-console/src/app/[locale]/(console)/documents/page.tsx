import { notFound } from "next/navigation";
import { CapabilityPage } from "@/features/capabilities/CapabilityPage";
import { DocumentWorkbench } from "@/features/documents/DocumentWorkbench";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface DocumentPageProps {
  readonly params: Promise<{ locale: string }>;
  readonly searchParams: Promise<{ cursor?: string | string[] }>;
}

export default async function DocumentsPage({ params, searchParams }: DocumentPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  const rawCursor = (await searchParams).cursor;
  if (Array.isArray(rawCursor)) {
    return <CapabilityPage id="documents" locale={locale} />;
  }
  try {
    const page = await getConsoleDataAccess().getDocumentPage({
      ...(rawCursor ? { cursor: rawCursor } : {}),
      limit: 25,
    });
    return <DocumentWorkbench locale={locale} page={page} />;
  } catch {
    // The capability placeholder is deliberate fail-closed degradation. It
    // never renders an upstream error, URL, token, or fabricated document.
    return <CapabilityPage id="documents" locale={locale} />;
  }
}
