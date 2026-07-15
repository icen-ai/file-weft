import { notFound } from "next/navigation";
import { Dashboard } from "@/features/dashboard/Dashboard";
import { isLocale } from "@/i18n/locale";

interface DashboardRouteProps {
  readonly params: Promise<{ locale: string }>;
}

export default async function DashboardRoute({ params }: DashboardRouteProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  return <Dashboard locale={locale} />;
}
