# JWT Authentication — Implementation Plan

> Spec: `specs/jwt-authentication.md`
> Branch: `jwt_authentication`
> Status: **37 / 37 tasks complete** — implementation complete

## Legend

- [ ] Not started
- [x] Complete

---

## 1. Dependencies & Configuration

- [x] **1.1 Add JWT dependencies to `build.gradle.kts`**
  - `at.favre.lib:bcrypt:0.10.2` — BCrypt password hashing
  - `io.ktor:ktor-server-auth-jwt:$ktor_version` — Ktor JWT authentication plugin
  - `com.auth0:java-jwt:4.5.0` — JWT token creation and verification

- [x] **1.2 Add admin/JWT config to `application.conf`**
  - Add `admin.email` / `admin.password` with env var overrides (`ADMIN_EMAIL`, `ADMIN_PASSWORD`)
  - Add `jwt.secret` (required, from `JWT_SECRET`) and `jwt.expiry` (default 3 minutes, from `JWT_EXPIRY_MINUTES`)
  - Remove `api.username` / `api.password` basic auth config

- [x] **1.3 Update `config/AppConfig.kt`**
  - Add `adminEmail`, `adminPassword`, `jwtSecret`, `jwtExpiry` properties to `AppConfig` interface
  - Implement in `DefaultAppConfig` — `jwtSecret` must fail fast if missing (no default value)
  - Remove `authUsername` / `authPassword` properties

---

## 2. Database Migrations

- [x] **2.1 Create `V13__create_vendors_table.sql`**
- [x] **2.2 Create `V14__recreate_vendor_audit_table.sql`**

---

## 3. Domain Layer

- [x] **3.1 Create `domain/model/Vendor.kt`**
- [x] **3.2 Create `domain/repository/VendorRepository.kt`**
- [x] **3.3 Create `domain/service/AuthService.kt`**
- [x] **3.4 Update `domain/error/DomainError.kt`**
- [x] **3.5 Create `domain/model/AuditContext.kt`**
- [x] **3.6 Update `domain/repository/AuditRepository.kt`**
- [x] **3.7 Update `domain/service/VersionService.kt` interface**
- [x] **3.8 Update `domain/service/TagService.kt` interface**

---

## 4. Application Layer

- [x] **4.1 Create `application/service/AuthServiceImpl.kt`**
- [x] **4.2 Implement JWT token creation**
- [x] **4.3 Create `application/validation/VendorRequestValidator.kt`**
- [x] **4.4 Update `VersionServiceImpl.kt` audit calls**
- [x] **4.5 Update `TagServiceImpl.kt` audit calls**

---

## 5. Adapter Layer — Secondary (Persistence)

- [x] **5.1 Create `adapter/secondary/persistence/PostgresVendorRepository.kt`**
- [x] **5.2 Update `adapter/secondary/persistence/PostgresAuditRepository.kt`**

---

## 6. Adapter Layer — Primary (REST)

- [x] **6.1 Replace basic auth with JWT in `config/Authentication.kt`**
- [x] **6.2 Create `adapter/primary/rest/AdminRoutes.kt`**
- [x] **6.3 Update `adapter/primary/rest/Routing.kt`**
- [x] **6.4 Update `adapter/primary/rest/RequestExtensions.kt`**
- [x] **6.5 Update `adapter/primary/rest/VersionRoutes.kt`**
- [x] **6.6 Update `adapter/primary/rest/TagRoutes.kt`**
- [x] **6.7 Update error handling in `RequestExtensions.kt`**

---

## 7. Application Entry Point

- [x] **7.1 Update `Application.kt`**

---

## 8. Test Infrastructure

- [x] **8.1 Create `test/support/JwtTestSupport.kt`**
- [x] **8.2 Update `test/support/Application.kt`**
- [x] **8.3 Update `test/support/Postgres.kt`**

---

## 9. Acceptance Tests

- [x] **9.1 Create `acceptance/AdminLoginAcceptanceSpec.kt`**
- [x] **9.2 Create `acceptance/AdminVendorManagementAcceptanceSpec.kt`**
- [x] **9.3 Create `acceptance/JwtAuthorizationAcceptanceSpec.kt`**
- [x] **9.4 Update existing acceptance tests**

---

## 10. Integration Tests

- [x] **10.1 Create `adapter/secondary/persistence/PostgresVendorRepositoryIntegrationSpec.kt`**
- [x] **10.2 Update `adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt`**

---

## 11. Unit Tests

- [x] **11.1 Create `application/service/AuthServiceUnitSpec.kt`**
- [x] **11.2 Create `application/validation/VendorRequestValidatorSpec.kt`**
- [x] **11.3 Update `application/service/VersionServiceUnitSpec.kt`**
- [x] **11.4 Update `application/service/TagServiceUnitSpec.kt`**

---

## Implementation Notes

- Admin password is compared using constant-time `MessageDigest.isEqual` (plaintext config value, not BCrypt)
- Vendor passwords use BCrypt with cost factor 12
- `JwtPrincipal` moved to its own file to satisfy detekt's `MatchingDeclarationName` rule
- `adminVendorRoutes` split into 3 route functions to satisfy detekt's `LongMethod` rule
- `VendorResponse.deletedAt` uses `Option<String>` (Arrow serialization) to avoid nullable types per project rules
