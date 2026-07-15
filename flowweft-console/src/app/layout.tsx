import type { Metadata } from "next";
import { headers } from "next/headers";
import { documentLanguage, parseLocale } from "@/i18n/locale";
import "./globals.css";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: {
    default: "FlowWeft Console",
    template: "%s · FlowWeft Console",
  },
  description: "FlowWeft 1.0 production console foundation",
  applicationName: "FlowWeft Console",
  robots: {
    index: false,
    follow: false,
  },
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const requestHeaders = await headers();
  const locale = parseLocale(requestHeaders.get("x-flowweft-locale"));

  return (
    <html lang={documentLanguage(locale)}>
      <body>
        {children}
        <div aria-hidden="true" className="paper-grain" />
      </body>
    </html>
  );
}
