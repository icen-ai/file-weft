import { notFound, redirect } from "next/navigation";
import { ConsoleShell } from "@/components/shell/ConsoleShell";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface ProductLayoutProps {
  readonly children: React.ReactNode;
  readonly params: Promise<{ locale: string }>;
}

export default async function ProductLayout({ children, params }: ProductLayoutProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  const session = await getConsoleDataAccess().getSession();
  if (!session) {
    redirect(`/${locale}/login`);
  }
  return <ConsoleShell locale={locale} session={session}>{children}</ConsoleShell>;
}
