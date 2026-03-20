# JWT Authentication

> Status: DRAFT — design decisions captured, pending review

## Overview

Replace Basic Authentication with stateless JWT-based authentication. This is a hard cutover — no transition period, Basic Auth removed entirely. Only two controlled clients consume the API, making a clean switch feasible.

## Domain Model

### Users Table

A new `users` table stores publisher credentials and their authorized candidate list.

```sql
CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      TEXT NOT NULL UNIQUE,
    password   TEXT NOT NULL,  -- BCrypt hash
    candidates TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

- `candidates` is a PostgreSQL text array — no join table needed
- Multi-candidate publishers are supported (a user can publish versions for multiple candidates)
- Password stored as BCrypt hash via `at.favre.lib:bcrypt:0.10.2`

### JWT Token

- **Expiry:** 10 minutes (short enough for breach protection)
- **No refresh tokens** — clients re-authenticate when token expires
- **Payload claims:**
  - `sub` — user email
  - `candidates` — list of authorized candidate names
  - `iat` — issued at
  - `exp` — expiration

Authorization is fully stateless — the candidates list in the JWT payload is sufficient for per-request authorization without database lookups.

## API Endpoints

### `POST /admin/users` — Create or Update User

**Authentication:** Admin credentials (environment variable or config-based)

**Behaviour:** Idempotent — handles create, update candidates, and password reset in one endpoint. If the user exists (by email), update their candidates list and/or password. If not, create a new user.

**Request Body:**
```json
{
  "email": "vendor@example.com",
  "password": "plaintext-password",
  "candidates": ["java", "kotlin"]
}
```

**Responses:**
| Status | Description |
|--------|-------------|
| `204 No Content` | User created or updated successfully |
| `400 Bad Request` | Validation failed (invalid email, empty password, etc.) |
| `401 Unauthorized` | Missing or invalid admin credentials |

**Notes:**
- No separate reset-password endpoint — the idempotent create covers password resets
- No list-users endpoint — admin can query the database directly if needed
- No admin audit trail for user management operations — only vendor operations are audited
- Plaintext password delivered to vendors via GPG-encrypted side-channel (out of scope for this service)

### `POST /admin/login` — Authenticate and Obtain Token

**Authentication:** None (public endpoint)

**Request Body:**
```json
{
  "email": "vendor@example.com",
  "password": "plaintext-password"
}
```

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
1. Check admin email first (environment-configured admin account)
2. Then check users table

### Authenticated Endpoints (JWT Required)

All existing write endpoints require a valid JWT token in the `Authorization: Bearer <token>` header:

| Endpoint | Authorization Rule |
|----------|-------------------|
| `POST /versions` | JWT valid AND `candidate` in token's `candidates` claim |
| `DELETE /versions` | JWT valid AND `candidate` in token's `candidates` claim |
| `DELETE /versions/tags` | JWT valid AND `candidate` in token's `candidates` claim |
| `POST /admin/users` | Admin credentials only (separate from JWT) |

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
| `401 Unauthorized` | `"Unauthorized"` | Missing token, expired token, invalid token, bad login credentials |
| `403 Forbidden` | `"Forbidden"` | Valid token but candidate not in authorized list |

## Vendor Audit Table Changes

The `vendor_audit` table is recreated with a nil UUID sentinel for admin-initiated operations (replacing nullable user references).

- Old `audit` table is **untouched** — it is read-only, consumed by `sdkman-broker-2`
- The `vendor_audit` table used for tracking version/tag mutations gets a Flyway migration to support the new user model

## Database Migration

- **V13:** Create `users` table
- **V14:** Recreate `vendor_audit` table with nil UUID sentinel column for admin operations

## Implementation Notes

### Dependencies

- `at.favre.lib:bcrypt:0.10.2` — BCrypt password hashing
- Ktor JWT authentication plugin (`io.ktor:ktor-server-auth-jwt`)
- JWT library (e.g., `com.auth0:java-jwt` or `io.jsonwebtoken:jjwt`)

### Hard Cutover

- Remove `Authentication.kt` basic auth configuration entirely
- Replace with JWT authentication plugin
- Update all `authenticate("auth-basic")` blocks to use JWT scheme
- No backward compatibility shim needed (only 2 controlled clients)

## Testing Strategy

Outside-in Kotest ShouldSpec acceptance tests.

### Happy Path Tests
1. Create a new user via `POST /admin/users` — 204
2. Login with valid credentials via `POST /admin/login` — 200 with token
3. Use JWT token to `POST /versions` for an authorized candidate — 204
4. Use JWT token to `DELETE /versions` for an authorized candidate — 204
5. Use JWT token to `DELETE /versions/tags` for an authorized candidate — 204
6. Update existing user (idempotent create) — 204, new candidates/password applied
7. Login after password reset — 200 with new token containing updated candidates

### Unhappy Path Tests
1. `POST /admin/login` with wrong password — 401
2. `POST /admin/login` with non-existent email — 401
3. `POST /versions` without token — 401
4. `POST /versions` with expired token — 401
5. `POST /versions` with valid token but unauthorized candidate — 403
6. `DELETE /versions` with valid token but unauthorized candidate — 403
7. `POST /admin/users` without admin credentials — 401
8. `POST /admin/users` with invalid email — 400
9. `POST /admin/users` with empty password — 400

## Open Questions

None — all design decisions resolved during interview session.
