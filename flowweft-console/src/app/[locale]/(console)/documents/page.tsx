import { notFound } from "next/navigation";
import { CapabilityPage } from "@/features/capabilities/CapabilityPage";
import { DocumentWorkbench } from "@/features/documents/DocumentWorkbench";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface DocumentPageProps {
  readonly params: Promise<{ locale: string }>;
  readonly searchParams: Promise<{
    cursor?: string | string[];
    lifecycleState?: string | string[];
    folderId?: string | string[];
    documentId?: string | string[];
  }>;
}

export default async function DocumentsPage({ params, searchParams }: DocumentPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  const query = await searchParams;
  if (Object.values(query).some(Array.isArray)) {
    return <CapabilityPage id="documents" locale={locale} />;
  }
  const rawCursor = query.cursor as string | undefined;
  const lifecycleState = query.lifecycleState as string | undefined;
  const folderId = query.folderId as string | undefined;
  const documentId = query.documentId as string | undefined;
  try {
    const dataAccess = getConsoleDataAccess();
    const page = await dataAccess.getDocumentPage({
      ...(rawCursor ? { cursor: rawCursor } : {}),
      ...(lifecycleState ? { lifecycleState } : {}),
      ...(folderId ? { folderId } : {}),
      limit: 25,
    });
    let detail = null;
    let selectionUnavailable = false;
    if (documentId) {
      try {
        detail = await dataAccess.getDocumentDetail(documentId);
      } catch {
        selectionUnavailable = true;
      }
    }
    return <DocumentWorkbench
      locale={locale}
      page={page}
      query={{ lifecycleState, folderId }}
      selectedDocumentId={documentId ?? null}
      detail={detail}
      selectionUnavailable={selectionUnavailable}
    />;
  } catch {
    // The capability placeholder is deliberate fail-closed degradation. It
    // never renders an upstream error, URL, token, or fabricated document.
    return <CapabilityPage id="documents" locale={locale} />;
  }
}
