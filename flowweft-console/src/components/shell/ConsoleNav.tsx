"use client";

import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  localizePath,
  navigationSections,
  type NavigationGroupId,
  type NavigationId,
} from "@/config/navigation";
import type { Locale } from "@/i18n/locale";

export interface ConsoleNavProps {
  readonly locale: Locale;
  readonly groupLabels: Record<NavigationGroupId, string>;
  readonly labels: Record<NavigationId, string>;
}

export function ConsoleNav({ locale, groupLabels, labels }: ConsoleNavProps) {
  const pathname = usePathname();

  return (
    <nav className="console-nav" aria-label={locale === "zh" ? "主导航" : "Primary navigation"}>
      {navigationSections.map((section) => (
        <section className="console-nav__section" key={section.id}>
          <h2>{groupLabels[section.id]}</h2>
          <div className="console-nav__items">
            {section.items.map((item) => {
              const href = localizePath(locale, item.segment);
              const active = item.segment === ""
                ? pathname === href
                : pathname === href || pathname.startsWith(`${href}/`);

              return (
                <Link
                  aria-current={active ? "page" : undefined}
                  className="console-nav__item"
                  href={href as Route}
                  key={item.id}
                >
                  <span>{item.code}</span>
                  <strong>{labels[item.id]}</strong>
                </Link>
              );
            })}
          </div>
        </section>
      ))}
    </nav>
  );
}
