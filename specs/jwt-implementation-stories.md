# JWT Authentication Implementation Stories

## Foundation (Stories 1-4)

- [ ] **Story 1: Database Schema and Migrations**
  - Create users table migration
  - Create vendor_audit table migration
  - Verify migrations run successfully
  - ~100 lines of SQL + test

- [ ] **Story 2: Security Domain Model**
  - Implement all domain data classes (User, JwtClaims, LoginRequest/Response, etc.)
  - Implement value objects (GeneratedPassword, PasswordHash, JwtSecret)
  - Implement AuthContext and authorization logic
  - ~200 lines of Kotlin

- [ ] **Story 3: Cryptography Layer**
  - Password generation utility (SecureRandom)
  - BCrypt password hashing/verification
  - JWT generation service
  - JWT validation service
  - Configuration for JWT secret
  - ~150 lines of Kotlin + tests

- [ ] **Story 4: In-Memory Admin User**
  - Load admin credentials from environment variables
  - Create Admin user instance at startup
  - Update application configuration
  - ~50 lines of Kotlin

## Authentication (Stories 5-6)

- [ ] **Story 5: Login Endpoint**
  - Implement POST /auth/login
  - Admin user authentication path
  - Database user authentication path
  - Return JWT token and expiry
  - ~100 lines of Kotlin + tests

- [ ] **Story 6: JWT Authentication Middleware**
  - Configure Ktor JWT authentication plugin
  - Extract AuthContext from JWT claims
  - Request metadata extraction (IP, User-Agent)
  - ~80 lines of Kotlin + tests

## User Management (Stories 7-8)

- [ ] **Story 7: User Repository**
  - CRUD operations for users table
  - Find by email
  - Create/update user
  - Soft delete (set active=false)
  - Candidate validation against versions table
  - ~120 lines of Kotlin + tests

- [ ] **Story 8: Admin User Management Endpoints**
  - POST /admin/users (create/update with idempotency)
  - DELETE /admin/users/{id}
  - POST /admin/users/{id}/reset-password
  - Authorization checks (admin only)
  - ~150 lines of Kotlin + tests

## Feature Integration (Stories 9-10)

- [ ] **Story 9: Update Protected Endpoints**
  - Replace Basic Auth with JWT auth on POST /versions
  - Replace Basic Auth with JWT auth on DELETE /versions
  - Implement candidate scope authorization
  - ~50 lines of changes + tests

- [ ] **Story 10: Vendor Audit Integration**
  - Create VendorAuditRepository
  - Integrate audit logging into POST /versions
  - Integrate audit logging into DELETE /versions
  - Integrate audit logging into admin endpoints
  - ~100 lines of Kotlin + tests

## Validation & Cleanup (Stories 11-12)

- [ ] **Story 11: Integration Tests**
  - End-to-end authentication flow tests
  - User creation and management tests
  - Protected endpoint authorization tests
  - Candidate scope enforcement tests
  - Audit trail verification tests
  - ~300 lines of test code

- [ ] **Story 12: Migration Cleanup**
  - Remove Basic Auth plugin and configuration
  - Update documentation
  - Verify all tests pass
  - ~20 lines of deletions

## Total Effort

- **12 stories**
- **~1,420 lines of new code**
- **Recommended order:** 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12
