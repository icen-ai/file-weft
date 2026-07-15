export interface ContentSecurityPolicyOptions {
  readonly nonce: string;
  readonly development: boolean;
}

export function buildContentSecurityPolicy({ nonce, development }: ContentSecurityPolicyOptions): string {
  const directives = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ""}`,
    "script-src-attr 'none'",
    `style-src 'self' 'nonce-${nonce}'${development ? " 'unsafe-inline'" : ""}`,
    "style-src-attr 'none'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "connect-src 'self'",
    "media-src 'none'",
    "object-src 'none'",
    "worker-src 'self' blob:",
    "manifest-src 'self'",
    "frame-src 'none'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ];

  if (!development) {
    directives.push("upgrade-insecure-requests");
  }

  return `${directives.join("; ")};`;
}
