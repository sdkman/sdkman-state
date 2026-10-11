# Configurable Bcrypt Cost

Passwords are hashed with bcrypt at a cost of 12, fixed in the constant `BCRYPT_COST`. That cost is
right for production and wrong for the test suite, which pays it hundreds of times over.

This feature moves the cost into configuration. Production keeps 12. The tests run at 4, the lowest
cost bcrypt allows. Nothing else about hashing or login changes.

## Behaviour

The bcrypt cost is read once at startup from `auth.bcrypt.cost`. Every hash the service makes uses
it: the admin, community and timing-dummy hashes `AuthServiceImpl` builds when it starts, and the
hash of each vendor password `POST /admin/vendors` generates.

Checking a password does not use the configured cost. A bcrypt hash records the cost it was made
with, and verification reads it from there. A vendor hashed at 12 still logs in after the cost
changes, and so does one hashed at 4.

## Business Rules

1. **The cost is declared once, in `application.conf`, as 12.** No Kotlin literal repeats it, and
   the `BCRYPT_COST` constant goes away.
2. **The cost is a required read.** A missing key fails startup.
3. **The cost has no environment override.** Unlike its `auth.rateLimit.enabled` sibling, nothing
   in production should ever run at a different cost, so there is no switch to flip by mistake. The
   only other value is the one the test config sets in code. Changing the production cost later
   also requires rehashing stored vendor passwords, because the timing dummy only masks a missing
   vendor while its cost matches theirs.
4. **A cost outside 4 to 31 fails startup.** Values inside that range are not sanity-checked.
   `AuthServiceImpl` hashes the admin password as it is built, so a rejected value throws before the
   service takes a request.
5. **The test config uses cost 4.** `testApplicationConfigWithoutCommunityAccount()` sets it, and
   every test config builds on that one.

## Findings

- **Hashing is most of the test suite's run time.** One cost-12 hash takes about 230 ms on a
  developer machine. Every `withTestApplication` builds a fresh `AuthServiceImpl`, the suite does
  that about 300 times, and the test JVM runs them one after another.
- **The community account made it worse.** Before it, each start made two hashes: admin and dummy.
  Adding the community account to the default test config made it three, about 70 s more per run.
  The CI "Run tests" step went from 5.1 min at `bc398bc` (run 38031607451) to 7.0, 6.3 and 7.1 min
  at `bb06245`, `2481235` and `fb4e384` (runs 38077756287, 38117420285 and 38120366337). Specs added
  in the same period account for part of the rest, and runner noise for the remainder. A local run went from 167 s to 285 s.
  The step time also covers the Gradle download and compilation, since CI caches neither.
- **Sharing one `AuthServiceImpl` across tests is not the fix.** Rate limiting is on in the test
  config, so a shared service would carry login attempt counts from one test into the next.
- **Cost 4 is about 256 times cheaper than cost 12.** Each step doubles the work, so the hashing
  cost of the suite becomes negligible.

## Examples

```gherkin
Feature: Configurable bcrypt cost

  Scenario: Tests hash at cost 4
    Given the service starts from the test config
    When an admin creates a vendor through POST /admin/vendors
    Then the vendor's stored password hash was made at cost 4

  Scenario: A hash made at another cost still verifies
    Given a vendor whose stored password hash was made at cost 12
      And the service runs at cost 4
    When the vendor logs in with the right password
    Then the response status is 200
```

The stored hash is observed from the vendor's database row, not from any response. No route or
response changes to expose it.

There is no scenario for the production cost of 12. Tests never load `application.conf`, and a test
that only re-states its value would be circular.

## Out of Scope

- **Changing the production cost.** It stays 12.
- **Rehashing stored vendor passwords.** No migration. Existing hashes keep verifying as they are.
- **Any change to login, the rate limiter or the timing-dummy behaviour.**
- **Any change to routes, responses, the OpenAPI document or the database schema.**
- **Other ways to speed up the suite,** such as running specs in parallel.

## Acceptance Criteria

- [ ] `application.conf` declares `auth.bcrypt.cost = 12`, with no environment override
- [ ] `AppConfig` exposes the cost, and `DefaultAppConfig` reads it as a required property
- [ ] A missing cost fails startup, and a test proves it
- [ ] A cost outside 4 to 31 fails startup, and a test proves it
- [ ] `AuthServiceImpl` and `POST /admin/vendors` hash with the configured cost
- [ ] `grep -rn BCRYPT_COST src` finds nothing, and `security/SecurityConstants.kt` is gone
- [ ] `testApplicationConfigWithoutCommunityAccount()` sets the cost to 4
- [ ] `AuthServiceImplUnitSpec` supplies the cost in both the initial mock and the `beforeEach`
      re-stub, and builds its test hashes at cost 4
- [ ] A vendor whose hash was made at cost 12 logs in while the service runs at cost 4
- [ ] No unit test re-asserts the HOCON default of 12
- [ ] The median CI "Run tests" step on `ubuntu-latest`, over 3 runs of this branch, is below
      5.5 min
- [ ] The project's quality gates pass
