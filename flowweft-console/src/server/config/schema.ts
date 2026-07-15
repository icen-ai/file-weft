import { z } from "zod";
import type { Locale } from "@/i18n/locale";

export const sourceProfileIdSchema = z
  .string()
  .min(1)
  .max(80)
  .regex(/^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/, "Source profile IDs must be opaque lowercase identifiers.");

const sourceAuthenticationModeSchema = z.enum(["OIDC_PKCE", "HOST_TOKEN_EXCHANGE"]);

const oidcAlgorithmSchema = z.enum(["RS256", "PS256", "ES256"]);

const hostTokenExchangeProfileSchema = z
  .object({
    endpointPath: z.string().min(2).max(512),
    allowPrivateNetwork: z.boolean().default(false),
  })
  .strict()
  .superRefine((value, context) => {
    if (!/^\/(?:[A-Za-z0-9_~-][A-Za-z0-9._~-]*\/)*[A-Za-z0-9_~-][A-Za-z0-9._~-]*$/u.test(value.endpointPath) ||
      value.endpointPath.split("/").some((segment) => segment === "." || segment === "..")) {
      context.addIssue({
        code: "custom",
        path: ["endpointPath"],
        message: "Host token exchange endpoint must be a canonical absolute path without parameters.",
      });
    }
  });

const sourceApiProfileSchema = z.object({
  allowPrivateNetwork: z.boolean().default(false),
}).strict();

const oidcProfileSchema = z
  .object({
    issuer: z.url().max(2_048),
    authorizationEndpoint: z.url().max(2_048),
    tokenEndpoint: z.url().max(2_048),
    jwksUri: z.url().max(2_048),
    clientId: z.string().min(1).max(256),
    scopes: z.array(z.string().regex(/^[A-Za-z0-9._:-]{1,80}$/u)).min(1).max(32),
    tenantAliasClaim: z.string().regex(/^[A-Za-z_][A-Za-z0-9_.:-]{0,79}$/u),
    displayNameClaim: z.string().regex(/^[A-Za-z_][A-Za-z0-9_.:-]{0,79}$/u).default("name"),
    allowedAlgorithms: z.array(oidcAlgorithmSchema).min(1).max(4).default(["RS256", "PS256", "ES256"]),
    allowPrivateNetwork: z.boolean().default(false),
  })
  .strict()
  .superRefine((value, context) => {
    if (!value.scopes.includes("openid")) {
      context.addIssue({
        code: "custom",
        path: ["scopes"],
        message: "OIDC scopes must contain openid.",
      });
    }
    if (new Set(value.scopes).size !== value.scopes.length) {
      context.addIssue({
        code: "custom",
        path: ["scopes"],
        message: "OIDC scopes must be unique.",
      });
    }
    if (new Set(value.allowedAlgorithms).size !== value.allowedAlgorithms.length) {
      context.addIssue({
        code: "custom",
        path: ["allowedAlgorithms"],
        message: "OIDC signing algorithms must be unique.",
      });
    }
  });

const sourceProfileDefinitionSchema = z
  .object({
    id: sourceProfileIdSchema,
    displayName: z.string().trim().min(1).max(120),
    baseUrl: z.url().max(2_048),
    authenticationModes: z.array(sourceAuthenticationModeSchema).min(1).max(2),
    oidc: oidcProfileSchema.optional(),
    hostTokenExchange: hostTokenExchangeProfileSchema.optional(),
    api: sourceApiProfileSchema.optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (new Set(value.authenticationModes).size !== value.authenticationModes.length) {
      context.addIssue({
        code: "custom",
        path: ["authenticationModes"],
        message: "Source profile authentication modes must be unique.",
      });
    }
    if ([...value.displayName].some((character) => /[\u0000-\u001f\u007f]/u.test(character))) {
      context.addIssue({
        code: "custom",
        path: ["displayName"],
        message: "Source profile display name contains a control character.",
      });
    }
    if (value.oidc && !value.authenticationModes.includes("OIDC_PKCE")) {
      context.addIssue({
        code: "custom",
        path: ["oidc"],
        message: "OIDC configuration requires the OIDC_PKCE authentication mode.",
      });
    }
    if (value.hostTokenExchange && !value.authenticationModes.includes("HOST_TOKEN_EXCHANGE")) {
      context.addIssue({
        code: "custom",
        path: ["hostTokenExchange"],
        message: "Host token exchange configuration requires the HOST_TOKEN_EXCHANGE authentication mode.",
      });
    }
  });

const sourceProfileDocumentSchema = z
  .object({
    version: z.literal(1),
    profiles: z.array(sourceProfileDefinitionSchema).max(100),
  })
  .strict()
  .superRefine((value, context) => {
    const ids = value.profiles.map((profile) => profile.id);
    if (new Set(ids).size !== ids.length) {
      context.addIssue({
        code: "custom",
        path: ["profiles"],
        message: "Source profile definitions must have unique IDs.",
      });
    }
  });

const sourceProfilesJsonSchema = z
  .string()
  .default("")
  .transform((raw, context): unknown => {
    if (raw.trim() === "") {
      return { version: 1, profiles: [] };
    }
    try {
      return JSON.parse(raw) as unknown;
    } catch {
      context.addIssue({
        code: "custom",
        message: "Source profile JSON is invalid.",
      });
      return z.NEVER;
    }
  })
  .pipe(sourceProfileDocumentSchema);

const sourceProfileIdsSchema = z
  .string()
  .default("")
  .transform((value) => value.split(",").map((part) => part.trim()).filter(Boolean))
  .pipe(z.array(sourceProfileIdSchema).max(100))
  .transform((value) => [...new Set(value)]);

const sessionEncryptionKeySchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/u),
  key: z.string().regex(/^[A-Za-z0-9_-]{43}$/u),
}).strict().superRefine((value, context) => {
  const decoded = Buffer.from(value.key, "base64url");
  if (decoded.length !== 32 || decoded.toString("base64url") !== value.key) {
    context.addIssue({ code: "custom", path: ["key"], message: "Session encryption keys must be canonical 256-bit base64url values." });
  }
});

const sessionEncryptionKeysJsonSchema = z.string().default("").transform((raw, context): unknown => {
  if (raw.trim() === "") {
    return [];
  }
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    context.addIssue({ code: "custom", message: "Session encryption key JSON is invalid." });
    return z.NEVER;
  }
}).pipe(z.array(sessionEncryptionKeySchema).max(4)).superRefine((value, context) => {
  if (new Set(value.map((entry) => entry.id)).size !== value.length) {
    context.addIssue({ code: "custom", message: "Session encryption key IDs must be unique." });
  }
});

const booleanStringSchema = z
  .enum(["true", "false"])
  .default("true")
  .transform((value) => value === "true");

const falseByDefaultBooleanStringSchema = z
  .enum(["true", "false"])
  .default("false")
  .transform((value) => value === "true");

function boundedIntegerStringSchema(defaultValue: number, minimum: number, maximum: number) {
  return z
    .string()
    .regex(/^[0-9]+$/u)
    .default(String(defaultValue))
    .transform((value) => Number(value))
    .pipe(z.number().int().min(minimum).max(maximum));
}

const optionalOriginSchema = z
  .string()
  .default("")
  .superRefine((value, context) => {
    if (value === "") {
      return;
    }
    let endpoint: URL;
    try {
      endpoint = new URL(value);
    } catch {
      context.addIssue({ code: "custom", message: "Console public origin is invalid." });
      return;
    }
    if (
      endpoint.username !== "" ||
      endpoint.password !== "" ||
      endpoint.pathname !== "/" ||
      endpoint.search !== "" ||
      endpoint.hash !== ""
    ) {
      context.addIssue({ code: "custom", message: "Console public origin must be an origin only." });
    }
  });

const consoleEnvironmentSchema = z
  .object({
    NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
    FLOWWEFT_CONSOLE_DEFAULT_LOCALE: z.enum(["zh", "en"]).default("zh"),
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: sourceProfileIdsSchema,
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: sourceProfilesJsonSchema,
    FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: optionalOriginSchema,
    FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME: z
      .string()
      .min(1)
      .max(80)
      .regex(/^[_A-Za-z0-9-]+$/, "Cookie name contains unsupported characters.")
      .default("__Host-flowweft_session"),
    FLOWWEFT_CONSOLE_SECURE_COOKIES: booleanStringSchema,
    FLOWWEFT_CONSOLE_SESSION_TTL_SECONDS: boundedIntegerStringSchema(3_600, 60, 86_400),
    FLOWWEFT_CONSOLE_AUTHORIZATION_TTL_SECONDS: boundedIntegerStringSchema(300, 60, 600),
    FLOWWEFT_CONSOLE_MAX_PENDING_AUTHORIZATIONS: boundedIntegerStringSchema(1_000, 1, 100_000),
    FLOWWEFT_CONSOLE_MAX_SESSIONS: boundedIntegerStringSchema(10_000, 1, 100_000),
    FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK: falseByDefaultBooleanStringSchema,
    FLOWWEFT_CONSOLE_REDIS_URL: z.string().max(2_048).default(""),
    FLOWWEFT_CONSOLE_REDIS_KEY_PREFIX: z.string()
      .regex(/^[A-Za-z0-9][A-Za-z0-9:_-]{0,127}$/u)
      .default("flowweft:console:v1"),
    FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON: sessionEncryptionKeysJsonSchema,
  })
  .superRefine((value, context) => {
    if (value.FLOWWEFT_CONSOLE_PUBLIC_ORIGIN !== "") {
      const publicOrigin = new URL(value.FLOWWEFT_CONSOLE_PUBLIC_ORIGIN);
      const publicProtocolAllowed = publicOrigin.protocol === "https:" ||
        value.NODE_ENV !== "production" && publicOrigin.protocol === "http:";
      if (!publicProtocolAllowed) {
        context.addIssue({
          code: "custom",
          path: ["FLOWWEFT_CONSOLE_PUBLIC_ORIGIN"],
          message: "Production console public origin requires HTTPS.",
        });
      }
    }
    const configuredIds = value.FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON.profiles.map((profile) => profile.id);
    const allowedIds = value.FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS;
    if (configuredIds.length > 0 &&
      (configuredIds.some((id) => !allowedIds.includes(id)) || allowedIds.some((id) => !configuredIds.includes(id)))) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON"],
        message: "Configured source profile IDs must exactly match the administrator allowlist.",
      });
    }
    for (const [index, profile] of value.FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON.profiles.entries()) {
      const endpoint = new URL(profile.baseUrl);
      if (
        endpoint.username !== "" ||
        endpoint.password !== "" ||
        endpoint.search !== "" ||
        endpoint.hash !== "" ||
        endpoint.pathname !== "/"
      ) {
        context.addIssue({
          code: "custom",
          path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON", "profiles", index, "baseUrl"],
          message: "Source profile base URL must be an origin without credentials, path, query, or fragment.",
        });
      }
      const allowedProtocol = endpoint.protocol === "https:" ||
        value.NODE_ENV !== "production" && endpoint.protocol === "http:";
      if (!allowedProtocol) {
        context.addIssue({
          code: "custom",
          path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON", "profiles", index, "baseUrl"],
          message: "Production source profiles require HTTPS.",
        });
      }
      if (profile.oidc) {
        const issuer = new URL(profile.oidc.issuer);
        const oidcEndpoints = [
          ["authorizationEndpoint", profile.oidc.authorizationEndpoint],
          ["tokenEndpoint", profile.oidc.tokenEndpoint],
          ["jwksUri", profile.oidc.jwksUri],
        ] as const;
        if (issuer.username !== "" || issuer.password !== "" || issuer.search !== "" || issuer.hash !== "") {
          context.addIssue({
            code: "custom",
            path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON", "profiles", index, "oidc", "issuer"],
            message: "OIDC issuer must not contain credentials, query, or fragment.",
          });
        }
        for (const [field, rawEndpoint] of oidcEndpoints) {
          const oidcEndpoint = new URL(rawEndpoint);
          if (
            oidcEndpoint.origin !== issuer.origin ||
            oidcEndpoint.username !== "" ||
            oidcEndpoint.password !== "" ||
            oidcEndpoint.search !== "" ||
            oidcEndpoint.hash !== ""
          ) {
            context.addIssue({
              code: "custom",
              path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON", "profiles", index, "oidc", field],
              message: "OIDC endpoints must use the exact issuer origin without credentials, query, or fragment.",
            });
          }
        }
        const oidcProtocolAllowed = issuer.protocol === "https:" ||
          value.NODE_ENV !== "production" && issuer.protocol === "http:";
        if (!oidcProtocolAllowed) {
          context.addIssue({
            code: "custom",
            path: ["FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON", "profiles", index, "oidc", "issuer"],
            message: "Production OIDC endpoints require HTTPS.",
          });
        }
      }
    }
    const redisConfigured = value.FLOWWEFT_CONSOLE_REDIS_URL !== "";
    const encryptionConfigured = value.FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON.length > 0;
    if (redisConfigured !== encryptionConfigured) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_REDIS_URL"],
        message: "Shared Redis sessions require both a Redis URL and at least one encryption key.",
      });
    }
    if (redisConfigured) {
      let redisUrl: URL | null = null;
      try {
        redisUrl = new URL(value.FLOWWEFT_CONSOLE_REDIS_URL);
      } catch {
        context.addIssue({ code: "custom", path: ["FLOWWEFT_CONSOLE_REDIS_URL"], message: "Redis URL is invalid." });
      }
      if (redisUrl && (!new Set(["redis:", "rediss:"]).has(redisUrl.protocol) || !redisUrl.hostname ||
        redisUrl.search !== "" || redisUrl.hash !== "" ||
        !/^(?:\/[0-9]{1,4})?$/u.test(redisUrl.pathname))) {
        context.addIssue({ code: "custom", path: ["FLOWWEFT_CONSOLE_REDIS_URL"], message: "Redis URL shape is unsafe." });
      }
      if (redisUrl && value.NODE_ENV === "production" && redisUrl.protocol !== "rediss:") {
        context.addIssue({ code: "custom", path: ["FLOWWEFT_CONSOLE_REDIS_URL"], message: "Production shared sessions require Redis TLS." });
      }
    }
    if (value.NODE_ENV !== "production") {
      return;
    }
    if (!value.FLOWWEFT_CONSOLE_SECURE_COOKIES) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_SECURE_COOKIES"],
        message: "Production sessions require Secure cookies.",
      });
    }
    if (!value.FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME.startsWith("__Host-")) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME"],
        message: "Production session cookies require the __Host- prefix.",
      });
    }
    if (value.FLOWWEFT_CONSOLE_PUBLIC_ORIGIN === "" &&
      value.FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON.profiles.some((profile) =>
        profile.oidc || profile.hostTokenExchange)) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_PUBLIC_ORIGIN"],
        message: "Production authentication requires an explicit console public origin.",
      });
    }
    if (!redisConfigured && !value.FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK &&
      value.FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON.profiles.some((profile) =>
        profile.oidc || profile.hostTokenExchange)) {
      context.addIssue({
        code: "custom",
        path: ["FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK"],
        message: "Production authentication requires an explicit acknowledgement of the single-replica session store.",
      });
    }
  });

export interface ConsoleServerConfig {
  readonly environment: "development" | "test" | "production";
  readonly defaultLocale: Locale;
  readonly allowedSourceProfileIds: readonly string[];
  readonly sourceProfiles: readonly ConsoleSourceProfileDefinition[];
  readonly publicOrigin: string | null;
  readonly sessionCookieName: string;
  readonly secureCookies: boolean;
  readonly sessionTtlSeconds: number;
  readonly authorizationTtlSeconds: number;
  readonly maximumPendingAuthorizations: number;
  readonly maximumSessions: number;
  readonly singleReplicaSessionStoreAcknowledged: boolean;
  readonly redis: ConsoleRedisSessionStoreDefinition | null;
}

export interface ConsoleSessionEncryptionKeyDefinition {
  readonly id: string;
  readonly key: string;
}

export interface ConsoleRedisSessionStoreDefinition {
  readonly url: string;
  readonly keyPrefix: string;
  readonly encryptionKeys: readonly ConsoleSessionEncryptionKeyDefinition[];
}

export interface ConsoleSourceProfileDefinition {
  readonly id: string;
  readonly displayName: string;
  readonly baseUrl: string;
  readonly authenticationModes: readonly ("OIDC_PKCE" | "HOST_TOKEN_EXCHANGE")[];
  readonly oidc?: ConsoleOidcProfileDefinition;
  readonly hostTokenExchange?: ConsoleHostTokenExchangeProfileDefinition;
  readonly api?: ConsoleSourceApiProfileDefinition;
}

export interface ConsoleSourceApiProfileDefinition {
  readonly allowPrivateNetwork: boolean;
}

export interface ConsoleHostTokenExchangeProfileDefinition {
  readonly endpointPath: string;
  readonly allowPrivateNetwork: boolean;
}

export interface ConsoleOidcProfileDefinition {
  readonly issuer: string;
  readonly authorizationEndpoint: string;
  readonly tokenEndpoint: string;
  readonly jwksUri: string;
  readonly clientId: string;
  readonly scopes: readonly string[];
  readonly tenantAliasClaim: string;
  readonly displayNameClaim: string;
  readonly allowedAlgorithms: readonly ("RS256" | "PS256" | "ES256")[];
  readonly allowPrivateNetwork: boolean;
}

export function parseConsoleServerConfig(
  environment: Readonly<Record<string, string | undefined>>,
): ConsoleServerConfig {
  const parsed = consoleEnvironmentSchema.parse(environment);
  const sourceProfiles = parsed.FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON.profiles.map((profile) => {
    const oidc = profile.oidc ? Object.freeze({
      issuer: profile.oidc.issuer,
      authorizationEndpoint: profile.oidc.authorizationEndpoint,
      tokenEndpoint: profile.oidc.tokenEndpoint,
      jwksUri: profile.oidc.jwksUri,
      clientId: profile.oidc.clientId,
      scopes: Object.freeze([...profile.oidc.scopes]),
      tenantAliasClaim: profile.oidc.tenantAliasClaim,
      displayNameClaim: profile.oidc.displayNameClaim,
      allowedAlgorithms: Object.freeze([...profile.oidc.allowedAlgorithms]),
      allowPrivateNetwork: profile.oidc.allowPrivateNetwork,
    }) : undefined;
    const hostTokenExchange = profile.hostTokenExchange ? Object.freeze({
      endpointPath: profile.hostTokenExchange.endpointPath,
      allowPrivateNetwork: profile.hostTokenExchange.allowPrivateNetwork,
    }) : undefined;
    const api = profile.api ? Object.freeze({
      allowPrivateNetwork: profile.api.allowPrivateNetwork,
    }) : undefined;
    return Object.freeze({
      id: profile.id,
      displayName: profile.displayName,
      baseUrl: new URL(profile.baseUrl).origin,
      authenticationModes: Object.freeze([...profile.authenticationModes]),
      ...(oidc ? { oidc } : {}),
      ...(hostTokenExchange ? { hostTokenExchange } : {}),
      ...(api ? { api } : {}),
    });
  });
  return Object.freeze({
    environment: parsed.NODE_ENV,
    defaultLocale: parsed.FLOWWEFT_CONSOLE_DEFAULT_LOCALE,
    allowedSourceProfileIds: Object.freeze(parsed.FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS),
    sourceProfiles: Object.freeze(sourceProfiles),
    publicOrigin: parsed.FLOWWEFT_CONSOLE_PUBLIC_ORIGIN === "" ? null : new URL(parsed.FLOWWEFT_CONSOLE_PUBLIC_ORIGIN).origin,
    sessionCookieName: parsed.FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME,
    secureCookies: parsed.FLOWWEFT_CONSOLE_SECURE_COOKIES,
    sessionTtlSeconds: parsed.FLOWWEFT_CONSOLE_SESSION_TTL_SECONDS,
    authorizationTtlSeconds: parsed.FLOWWEFT_CONSOLE_AUTHORIZATION_TTL_SECONDS,
    maximumPendingAuthorizations: parsed.FLOWWEFT_CONSOLE_MAX_PENDING_AUTHORIZATIONS,
    maximumSessions: parsed.FLOWWEFT_CONSOLE_MAX_SESSIONS,
    singleReplicaSessionStoreAcknowledged: parsed.FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK,
    redis: parsed.FLOWWEFT_CONSOLE_REDIS_URL === "" ? null : Object.freeze({
      url: parsed.FLOWWEFT_CONSOLE_REDIS_URL,
      keyPrefix: parsed.FLOWWEFT_CONSOLE_REDIS_KEY_PREFIX,
      encryptionKeys: Object.freeze(parsed.FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON.map((entry) =>
        Object.freeze({ id: entry.id, key: entry.key }))),
    }),
  });
}
