import { notFound, redirect } from "next/navigation";
import { LoginFoundation } from "@/features/auth/LoginFoundation";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface LoginRouteProps {
  readonly params: Promise<{ locale: string }>;
}

export default async function LoginRoute({ params }: LoginRouteProps) {
  const { locale } = await params;
  if (!isLocale(locale)) {
    notFound();
  }
  const dataAccess = getConsoleDataAccess();
  if (await dataAccess.getSession()) {
    redirect(`/${locale}`);
  }
  const sourceProfiles = await dataAccess.listSourceProfiles();
  return <LoginFoundation locale={locale} sourceProfiles={sourceProfiles} />;
}
