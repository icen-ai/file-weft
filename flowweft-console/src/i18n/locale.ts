export const locales = ["zh", "en"] as const;

export type Locale = (typeof locales)[number];

export function isLocale(value: string | null | undefined): value is Locale {
  return locales.includes(value as Locale);
}

export function parseLocale(value: string | null | undefined): Locale {
  return isLocale(value) ? value : "zh";
}

export function documentLanguage(locale: Locale): string {
  return locale === "zh" ? "zh-CN" : "en";
}
