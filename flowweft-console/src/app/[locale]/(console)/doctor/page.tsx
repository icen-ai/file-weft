import { notFound } from "next/navigation";
import { CapabilityPage } from "@/features/capabilities/CapabilityPage";
import { SystemDoctorWorkbench } from "@/features/doctor/SystemDoctorWorkbench";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface DoctorPageProps {
  readonly params: Promise<{ locale: string }>;
}

export default async function DoctorPage({ params }: DoctorPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  try {
    const report = await getConsoleDataAccess().getSystemDoctorReport();
    return <SystemDoctorWorkbench locale={locale} report={report} />;
  } catch {
    // Keep upstream details, topology and authorization distinctions out of
    // the rendered page. The capability frame is the explicit unavailable
    // state; it never fabricates a healthy report.
    return <CapabilityPage id="doctor" locale={locale} />;
  }
}
