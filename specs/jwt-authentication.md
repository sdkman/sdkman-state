# JWT Authentication

> Status: DRAFT — updated to reflect hexagonal architecture refactor

## Overview

Replace Basic Authentication with stateless JWT-based authentication. This is a hard cutover — no transition period, Basic Auth removed entirely. Only two controlled clients consume the API, making a clean switch feasible.

## Architecture

### Hexagonal Layer Mapping

| Layer | Component | Location |
|-------|-----------|----------|
| **Domain** | `Vendor` model | `domain/model/Vendor.kt` |
| **Domain** | `VendorRepository` port | `domain/repository/VendorRepository.kt` |
| **Domain** | `AuthService` port | `domain/service/AuthService.kt` |
| **Application** | `AuthServiceImpl` | `application/service/AuthServiceImpl.kt` |
| **Adapter/primary** | `AdminRoutes.kt` | `adapter/primary/rest/AdminRoutes.kt` |
| **Adapter/primary** | JWT auth plugin config | `config/Authentication.kt` (replace basic auth) |
| **Adapter/secondary** | `PostgresVendorRepository` | `adapter/secondary/persistence/PostgresVendorRepository.kt` |

### Hard Cutover

- Remove `Authentication.kt` basic auth configuration entirely
- Replace with JWT authentication plugin in the same file
- Update `authenticate("auth-basic")` block in `Routing.kt` to use JWT scheme
- No backward compatibility shim needed (only 2 controlled clients)

## Domain Model

### Vendors Table

A new `vendors` table stores vendor credentials and their authorized candidate list.

```sql
CREATE TABLE vendors (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      TEXT NOT NULL UNIQUE,
    password   TEXT NOT NULL,  -- BCrypt hash (cost factor 12)
    candidates TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE  -- NULL when active (soft delete)
);
```

- `candidates` is a PostgreSQL text array — no join table needed
- Multi-candidate vendors are supported (a vendor can publish versions for multiple candidates)
- Password stored as BCrypt hash via `at.favre.lib:bcrypt:0.10.2` with **cost factor 12**
- Password generation: **32 bytes from `SecureRandom`, base64-encoded** (~43 characters, ~192 bits of entropy)
- `deleted_at` is NULL for active vendors — soft-deleted vendors have a timestamp and cannot authenticate

### JWT Token

- **Algorithm:** HMAC-SHA256 — the verifier **must** be configured to accept only HS256 and reject all other algorithms including `none`
- **Secret:** Configured via `jwt.secret` / env var `JWT_SECRET`
- **Expiry:** Configurable via `jwt.expiry` (minutes), default 3 minutes — short-lived for CI/GitHub Actions clients
- **No refresh tokens** — clients re-authenticate when token expires
- **Payload claims:**
  - `iss` — `"sdkman-state"` (verified on every request)
  - `aud` — `"sdkman-state"` (verified on every request)
  - `sub` — email (admin or vendor)
  - `role` — `"admin"` or `"vendor"`
  - `candidates` — list of authorized candidate names (vendor only)
  - `iat` — issued at
  - `exp` — expiration

Authorization is fully stateless — the role and candidates list in the JWT payload are sufficient for per-request authorization without database lookups.

### Role-Based Authorization

| Role | `role` claim | `candidates` claim | Access |
|------|-------------|-------------------|--------|
| Admin | `"admin"` | not present | Carte blanche on all candidates |
| Vendor | `"vendor"` | `["java", "kotlin", ...]` | Restricted to listed candidates |

## Configuration

### Admin Account

The admin is a hardcoded account (not in the `vendors` table) that authenticates via `POST /admin/login` like any vendor. Configured via:

```hocon
admin {
    email = "admin@sdkman.io"
    email = ${?ADMIN_EMAIL}
    password = "changeme"
    password = ${?ADMIN_PASSWORD}
}
```

The admin must acquire a JWT (with `role: "admin"`) before accessing any protected endpoint. The `sub` claim is always an email for both admin and vendors, eliminating collision risk. Replaces the existing `api.username` / `api.password` config.

### JWT Secret

```hocon
jwt {
    secret = ${JWT_SECRET}   // required — app fails fast on startup if missing
    expiry = 3
    expiry = ${?JWT_EXPIRY_MINUTES}
}
```

The `JWT_SECRET` environment variable is **required** — the application will fail to start if it is not set. No default value is provided to prevent accidental deployment with a known secret.

## API Endpoints

### `GET /admin/vendors` — List All Vendors

**Authentication:** JWT with `role: "admin"`

**Responses:**
| Status | Description |
|--------|-------------|
| `200 OK` | Returns list of vendors |
| `401 Unauthorized` | Any failure — no information leakage |

**Success Response:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "vendor@example.com",
    "candidates": ["java", "kotlin"],
    "created_at": "2026-03-20T10:30:00Z",
    "updated_at": "2026-03-20T10:30:00Z",
    "deleted_at": null
  }
]
```

**Notes:**
- Returns all vendors including soft-deleted ones (`deleted_at` will be non-null for deleted vendors)
- Passwords are never included in the response (use `POST /admin/vendors` to see generated passwords)
- Always returns `401` on failure — no information leakage

### `POST /admin/vendors` — Create or Update Vendor

**Authentication:** JWT with `role: "admin"`

**Behaviour:** Idempotent — if the vendor exists (by email), update and regenerate password. If not, create a new vendor. If the vendor was previously soft-deleted, it is **resurrected** (clears `deleted_at`, regenerates password).

- `email` is validated against an RFC-compliant regex pattern
- `email` must not match the configured admin email — rejected with `400 Bad Request`
- `candidates` is **optional** — if omitted, the existing list is preserved. If provided, it **replaces** the entire list.
- Password is always regenerated on every call.

**Request Body:**
```json
{
  "email": "vendor@example.com",
  "candidates": ["java", "kotlin"]  // optional — omit to keep existing
}
```

**Responses:**
| Status | Description |
|--------|-------------|
| `200 OK` | Vendor created or updated, returns full vendor object with generated password |
| `400 Bad Request` | Validation failure (invalid email, empty candidates, email matches admin) |
| `401 Unauthorized` | Missing or invalid token, insufficient role |

**Success Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@example.com",
  "password": "generated-plaintext-password",
  "candidates": ["java", "kotlin"],
  "created_at": "2026-03-20T10:30:00Z",
  "updated_at": "2026-03-20T10:30:00Z"
}
```

**Notes:**
- The service generates the password — no password in the request
- A new password is generated on every call (idempotent on email/candidates, but always resets the password)
- The plaintext password is returned once and stored as a BCrypt hash — it cannot be retrieved again
- **No response body logging:** Ensure no middleware or application logging captures this endpoint's response (it contains plaintext passwords)
- Admin delivers credentials to vendors via GPG-encrypted side-channel (out of scope for this service)
- Returns `400` for validation failures (caller is authenticated, meaningful errors are safe)
- Returns `401` for authentication/authorization failures
- No admin audit trail for vendor management operations — only version/tag mutations are audited

### `DELETE /admin/vendors/{id}` — Soft Delete Vendor

**Authentication:** JWT with `role: "admin"`

**Behaviour:** Sets `deleted_at` to the current timestamp. The vendor can no longer authenticate but remains in the database. Existing JWTs for this vendor remain valid until they expire (max 3 minutes — accepted risk).

**Responses:**
| Status | Description |
|--------|-------------|
| `200 OK` | Vendor soft-deleted |
| `401 Unauthorized` | Missing or invalid token, insufficient role |
| `404 Not Found` | Vendor not found or already deleted |

**Success Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@example.com",
  "candidates": ["java", "kotlin"],
  "created_at": "2026-03-20T10:30:00Z",
  "updated_at": "2026-03-20T10:30:00Z",
  "deleted_at": "2026-03-20T11:00:00Z"
}
```

### `POST /admin/login` — Authenticate and Obtain Token

**Authentication:** None (public endpoint)

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password"
}
```

- For **admin**: uses credentials from `admin.email` / `admin.password` config
- For **vendors**: uses the service-generated password received from the admin

**Responses:**
| Status | Description |
|--------|-------------|
| `200 OK` | Returns JWT token |
| `401 Unauthorized` | Invalid credentials |

**Success Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

**Login Precedence:**
1. Check admin email first (environment-configured admin account) — issues token with `role: "admin"`
2. Then check vendors table — issues token with `role: "vendor"` and `candidates` claim

**Security:**
- **Constant-time behaviour:** Always perform a BCrypt comparison, even when the email is not found. Compare against a dummy hash (same cost factor 12 as real hashes) to prevent timing attacks that reveal whether an email exists.
- **Soft-deleted vendors:** Login attempts for soft-deleted vendors return `401` — the dummy hash comparison is still performed to prevent timing-based enumeration.
- **No response body logging:** Ensure no middleware or application logging captures login response bodies (they contain JWTs).

### Authenticated Endpoints (JWT Required)

All existing write endpoints require a valid JWT token in the `Authorization: Bearer <token>` header:

| Endpoint | Authorization Rule |
|----------|-------------------|
| `POST /versions` | JWT valid AND (`role: "admin"` OR `candidate` in token's `candidates` claim) |
| `DELETE /versions` | JWT valid AND (`role: "admin"` OR `candidate` in token's `candidates` claim) |
| `DELETE /versions/tags` | JWT valid AND (`role: "admin"` OR `candidate` in token's `candidates` claim) |
| `GET /admin/vendors` | JWT valid AND `role: "admin"` |
| `POST /admin/vendors` | JWT valid AND `role: "admin"` |
| `DELETE /admin/vendors/{id}` | JWT valid AND `role: "admin"` |

## Error Responses

All authentication/authorization errors use the existing `ErrorResponse` shape:

```json
{
  "error": "Unauthorized",
  "message": "Invalid or expired token"
}
```

| Status | Error | When |
|--------|-------|------|
| `400 Bad Request` | `"Bad Request"` | Validation failure on authenticated admin endpoints (invalid email, empty candidates, admin email collision) |
| `401 Unauthorized` | `"Unauthorized"` | Missing token, expired token, invalid token, bad login credentials |
| `403 Forbidden` | `"Forbidden"` | Valid token but candidate not in authorized list |
| `404 Not Found` | `"Not Found"` | Resource not found (e.g., vendor deletion of non-existent/already-deleted vendor) |

## Vendor Audit Table Changes

The existing `vendor_audit` table is dropped and recreated with the new schema (service is not yet live — no data to preserve).

- `vendor_id` is a plain UUID — **no foreign key** to `vendors` table (the nil UUID sentinel `00000000-0000-0000-0000-000000000000` for admin operations would violate a FK constraint)
- Old `audit` table is **untouched** — it is read-only, consumed by `sdkman-broker-2`

### Recreated Schema

```sql
CREATE TABLE vendor_audit (
    id            BIGSERIAL PRIMARY KEY,
    vendor_id     UUID NOT NULL,
    email         TEXT NOT NULL,
    timestamp     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    operation     TEXT NOT NULL,
    version_data  JSONB NOT NULL
);
```

## Database Migration

- **V13:** Create `vendors` table (includes `deleted_at` column for soft delete)
- **V14:** Drop and recreate `vendor_audit` table with `vendor_id` (UUID) and `email` (TEXT) columns

## Implementation Notes

### Dependencies

- `at.favre.lib:bcrypt:0.10.2` — BCrypt password hashing
- `io.ktor:ktor-server-auth-jwt` — Ktor JWT authentication plugin
- `com.auth0:java-jwt` — JWT token creation and verification

### Files to Modify

| File | Change |
|------|--------|
| `config/Authentication.kt` | Replace basic auth with JWT auth plugin |
| `config/AppConfig.kt` | Add `admin.email`, `admin.password`, `jwt.secret` (required), `jwt.expiry` config |
| `adapter/primary/rest/Routing.kt` | Update `authenticate("auth-basic")` to JWT scheme, add admin routes |
| `adapter/primary/rest/AdminRoutes.kt` | **New** — login and user management routes |
| `adapter/secondary/persistence/PostgresAuditRepository.kt` | Update to write `vendor_id` + `email` instead of `username` |
| `application/service/VersionServiceImpl.kt` | Pass JWT user details to audit |
| `application/service/TagServiceImpl.kt` | Pass JWT user details to audit |
| `Application.kt` | Wire new `AuthServiceImpl` and `PostgresVendorRepository` |

### Test Support

- **`JwtTestSupport`** utility in `test/support/` — generates valid, expired, and invalid tokens for test scenarios
- Update `test/support/Application.kt` to wire JWT auth instead of basic auth

## Testing Strategy

Outside-in Kotest ShouldSpec acceptance tests.

### Happy Path Tests
1. List vendors via `GET /admin/vendors` — 200 with empty list
2. Create a new vendor via `POST /admin/vendors` — 200 with generated password
3. Login with valid vendor credentials via `POST /admin/login` — 200 with token containing `role: "vendor"` and `candidates`
4. Login with admin credentials via `POST /admin/login` — 200 with token containing `role: "admin"`
5. Use vendor JWT token to `POST /versions` for an authorized candidate — 204
6. Use vendor JWT token to `DELETE /versions` for an authorized candidate — 204
7. Use vendor JWT token to `DELETE /versions/tags` for an authorized candidate — 204
8. Use admin JWT token to `POST /versions` for any candidate — 204
9. Update existing vendor (idempotent create) — 200, new candidates and regenerated password
10. List vendors after create — 200 with vendor details (no password), correct candidates
11. Login after password reset — 200 with new token containing updated candidates
12. Soft delete vendor via `DELETE /admin/vendors/{id}` — 200 with `deleted_at` set
13. List vendors after soft delete — 200, deleted vendor present with `deleted_at` non-null
14. Resurrect soft-deleted vendor via `POST /admin/vendors` with same email — 200, `deleted_at` cleared, new password

### Unhappy Path Tests
1. `POST /admin/login` with wrong password — 401
2. `POST /admin/login` with non-existent email — 401
3. `POST /admin/login` with soft-deleted vendor email — 401
4. `POST /versions` without token — 401
5. `POST /versions` with expired token — 401
6. `POST /versions` with valid vendor token but unauthorized candidate — 403
7. `DELETE /versions` with valid vendor token but unauthorized candidate — 403
8. `GET /admin/vendors` without token — 401
9. `GET /admin/vendors` with vendor token — 401 (no info leakage)
10. `POST /admin/vendors` without token — 401
11. `POST /admin/vendors` with vendor token — 401 (no info leakage)
12. `POST /admin/vendors` with admin token but invalid email — 400
13. `POST /admin/vendors` with admin token but empty candidates — 400
14. `POST /admin/vendors` with admin token but admin email — 400
15. `DELETE /admin/vendors/{id}` without token — 401
16. `DELETE /admin/vendors/{id}` with vendor token — 401 (no info leakage)
17. `DELETE /admin/vendors/{id}` with non-existent id — 404
18. `DELETE /admin/vendors/{id}` with already-deleted vendor — 404

## Open Questions

None — all design decisions resolved.
