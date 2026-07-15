import { notFound } from "next/navigation";
import type { CapabilityPageId } from "@/config/navigation";
import { CapabilityPage } from "@/features/capabilities/CapabilityPage";
import { isLocale } from "@/i18n/locale";

interface CapabilityRouteProps {
  readonly params: Promise<{ locale: string }>;
}

export function buildCapabilityRoute(id: CapabilityPageId) {
  return async function CapabilityRoute({ params }: CapabilityRouteProps) {
    const { locale } = await params;
    if (!isLocale(locale)) {
      notFound();
    }
    return <CapabilityPage id={id} locale={locale} />;
  };
}
