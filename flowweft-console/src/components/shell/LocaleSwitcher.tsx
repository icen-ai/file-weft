"use client";

import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { locales, type Locale } from "@/i18n/locale";

export interface LocaleSwitcherProps {
  readonly current: Locale;
  readonly label: string;
}

function replaceLocale(pathname: string, locale: Locale): string {
  if (/^\/(zh|en)(\/|$)/.test(pathname)) {
    return pathname.replace(/^\/(zh|en)(?=\/|$)/, `/${locale}`);
  }
  return `/${locale}`;
}

export function LocaleSwitcher({ current, label }: LocaleSwitcherProps) {
  const pathname = usePathname();

  return (
    <div className="locale-switcher" aria-label={label} role="group">
      {locales.map((locale) => (
        <Link
          aria-current={locale === current ? "true" : undefined}
          href={replaceLocale(pathname, locale) as Route}
          key={locale}
          lang={locale === "zh" ? "zh-CN" : "en"}
        >
          {locale === "zh" ? "中" : "EN"}
        </Link>
      ))}
    </div>
  );
}
