export const localeStorageKey = "flowweft-docs-locale";
export const legacyLocaleStorageKey = "fileweft-docs-locale";

const isSupportedLocale = (value) => value === "en" || value === "zh";

export function readLocale(storage) {
  try {
    const target = storage ?? globalThis.localStorage;
    const current = target.getItem(localeStorageKey);
    if (isSupportedLocale(current)) return current;

    const legacy = target.getItem(legacyLocaleStorageKey);
    if (isSupportedLocale(legacy)) {
      try {
        target.setItem(localeStorageKey, legacy);
        target.removeItem(legacyLocaleStorageKey);
      } catch {
        // The legacy preference still applies for this session if migration is blocked.
      }
      return legacy;
    }
  } catch {
    // Storage may be unavailable in hardened or private browsing contexts.
  }
  return "en";
}

export function saveLocale(locale, storage) {
  try {
    const target = storage ?? globalThis.localStorage;
    target.setItem(localeStorageKey, locale === "zh" ? "zh" : "en");
  } catch {
    // Preferences remain session-local when storage is unavailable.
  }
}
