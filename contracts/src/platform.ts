export type CredentialKind = "web_session" | "api" | "collector" | "display" | "enrollment";

export type PlatformScope =
  | "usage:read"
  | "history:read"
  | "devices:read"
  | "usage:write"
  | "enrollment:redeem";

export type ApiErrorCode =
  | "unauthorized"
  | "forbidden"
  | "not_found"
  | "invalid_ingest"
  | "invalid_signature"
  | "replayed_nonce"
  | "credential_limit"
  | "invalid_enrollment"
  | "csrf_required"
  | "recent_login_required";

export interface ApiErrorResponse {
  error: {
    code: ApiErrorCode;
    message: string;
    requestId: string;
  };
}

export const PLATFORM_ENDPOINTS = {
  auth: ["/v1/auth/config", "/v1/auth/google/start", "/v1/auth/google/callback", "/v1/auth/me", "/v1/auth/logout"],
  dashboard: ["/v1/dashboard", "/v1/history"],
  credentials: ["/v1/api-tokens", "/v1/collectors", "/v1/display-devices", "/v1/device-enrollments", "/v1/device-enrollments/redeem", "/v1/collector-enrollments", "/v1/collector-enrollments/redeem"],
  ingest: ["/v1/ingest/claude", "/v1/ingest/codex", "/v1/ingest/claude/token-usage", "/v1/ingest/codex/token-usage"],
} as const;
