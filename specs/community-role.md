# Community Role

State knows two kinds of caller today. The **admin** can do anything. A **vendor** can publish
versions and tags for the candidates in its scope, and nothing else. Neither fits the public
contribution path that replaces the archived `sdkman-db-migrations`: anyone opens a PR in
`sdkman-contrib`, a SDKMAN maintainer merges it, and a job writes it to State. That job
should not hold the admin's credential, and a vendor cannot register a candidate at all, so a
vendor login could never add a new one.

This feature adds a third role, **community**. It looks after candidates: it registers,
updates and deletes them, publishes, overwrites and deletes their versions, and manages their
tags. It never manages vendors.

Adding a third role exposes a defect: the version and tag routes treat any role that is not
`vendor` as admin. That is fixed here, before a third role exists to slip through.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §14
([`application-end-game.md`](../../../docs/specs/application-end-game.md)). The consumer is
[`sdkman-contrib`](../../../community/sdkman-contrib/specs/community-contributions.md).
Settled in a grilling session on 2026-10-07. Builds on [`jwt-authentication.md`](jwt-authentication.md).*

## Behaviour

**The community role is a single account.** It logs in like the admin and vendors do and
receives a token whose role is `community`. There is one community account, held by
`sdkman-contrib`'s apply job. Like the admin, it is config-backed rather than a row in
`vendors`:

```hocon
community {
    email = ${?COMMUNITY_EMAIL}
    password = ${?COMMUNITY_PASSWORD}
}
```

Login checks the admin first, then the community account, then the `vendors` table.

**The community account is optional.** It has no default credentials. A State instance with
no community account configured starts and behaves exactly as today: a community login simply
fails with `401`, like any wrong credential. The account counts as configured only when both
`community.email` and `community.password` are set; with either one missing it is treated as
absent. *(Assumption, planning pass 2026-10-08: half a credential is a misconfiguration, and
refusing it is the safer reading.)* So the release needs no deploy ordering, and the
local stack and the tests keep working without it.

**It may change every candidate.** On any candidate, registered or not, the role can do
everything the admin can do to candidates, versions and tags. Its scope has no exceptions and
depends on no data, so it cannot fail or go stale. Keeping community requests away from
java, which DISCO owns, is `sdkman-contrib`'s rule and its maintainers' review, not State's.
Admin-only operations outside candidates, versions and tags, such as managing vendors, stay
admin-only.

**Authorization fails closed.** A role gets only what it is explicitly granted. A token with a
role State does not know is refused by every write route. Today the version and tag routes
restrict a caller only when its role is `vendor` and treat any other role as admin; that
stops.

**Admin and vendor behaviour is unchanged.** Every status code either of them gets today, on
every route, stays the same.

## API Contract

No new routes. Existing routes admit the community role as follows:

| Route | Admin | Vendor | Community |
|---|---|---|---|
| `POST /versions` | any candidate | own candidates | any candidate |
| `DELETE /versions` | any candidate | own candidates | any candidate |
| `POST /versions/tags`, `DELETE /versions/tags` | any candidate | own candidates | any candidate |
| `POST /admin/candidates` | yes | no (401) | any candidate |
| `DELETE /admin/candidates/{candidate}` | yes | no (401) | any candidate |
| `/admin/vendors` (all) | yes | no (401) | no (401) |

Every rule on these routes applies to the community role unchanged: validation, the registry check on `POST /versions`,
and the `409` that refuses to delete a candidate that still has versions.

## Business Rules

1. **Authorization fails closed.** Each write route names the roles it admits; any other role,
   including one State does not recognise, is refused. This is the precondition for the rest:
   without it, the community role would pass the version and tag routes as admin. An unknown
   role gets `403` on the version and tag routes and `401` on the `/admin` routes, the same as
   each route already gives a role it doesn't allow.
2. **The community role cannot manage vendors.** It is refused every `/admin/vendors` route.
3. **Everything the admin may do to candidates, versions and tags, the community role may
   do to every candidate.** No candidate is refused, whatever any vendor's scope says. That includes overwriting, deleting and
   any tag; `sdkman-contrib` uses a subset, and State does not encode it.
4. **Community writes are attributed to the community account.** Where a vendor's write is
   recorded in the audit trail with its identity, a community write is recorded with the
   community account's. The community token's `vendor_id` is a fixed, documented sentinel UUID,
   distinct from the admin's nil UUID, namely `00000000-0000-0000-0000-000000000001`, and `email` is the community account's email, so a
   `vendor_audit` row can be attributed to the community by either column. The token's
   `candidates` claim is an empty list, as for the admin; the role's reach comes from the role,
   not from the claim.

## Rollout

**One release, no data migration.** Ship it before `sdkman-contrib`'s apply goes live. The
community account's credential lives in State's deploy environment and `sdkman-contrib`'s
secrets, nowhere else.

The fail-closed change ships in the same release. Only a role that doesn't exist before it can
observe it, so admin and vendor callers see nothing change.

**Rollback.** Redeploy the previous release. The community role vanishes, `sdkman-contrib`'s
apply fails loudly, and nothing already written is affected. Tokens issued before the rollback
keep working against the previous release's fail-open routes, as admin, until they expire
(10 minutes). Rotate the JWT secret or wait out the expiry before relying on the rollback.

## Findings

- **The version and tag routes fail open.** `POST /versions`, `DELETE /versions`,
  `POST /versions/tags` and `DELETE /versions/tags` each refuse a caller only when its role is
  `vendor` and the candidate is outside its scope; any other role passes as if it were admin.
  The `/admin/vendors` routes and the candidate admin routes test for `admin` explicitly and
  are already closed. Introducing a third role without fixing the first four would grant it
  admin rights on versions and tags.

## Examples

```gherkin
Feature: Community role

  Scenario: The community role registers a new candidate
    Given "jpx" is not registered
    When the community role registers "jpx"
    Then "jpx" is registered

  Scenario: The community role publishes, overwrites and deletes a version
    Given "jpx" is registered
    When the community role posts "jpx 1.2.0" for LINUX_X64
      And posts it again with a different URL
    Then "jpx 1.2.0" for LINUX_X64 has the new URL
    When the community role deletes it
    Then "jpx 1.2.0" for LINUX_X64 no longer exists

  Scenario: The community role may assign any tag
    Given "jpx 1.2.0" exists for LINUX_X64
    When the community role assigns the tag "latest" to it
    Then the response status is 204

  Scenario: A vendor's candidate is not refused
    Given a live vendor's scope includes "gradle"
    When the community role posts a version of "gradle"
    Then the version is accepted

  Scenario: The community role cannot manage vendors
    When the community role lists vendors
    Then the response status is 401

  Scenario: An unknown role is refused every write
    Given a validly signed token whose role is "observer"
    When it posts a version of "jpx"
    Then the response status is 403

  Scenario: Admin and vendor access is unchanged
    Given a vendor whose scope is "kotlin"
    When it posts a version of "jpx"
    Then the response status is 403
```

## Out of Scope

- Validating a request without writing it: [`public-validation.md`](public-validation.md).
- More than one community account, or a community scope narrower than every candidate.
- Refusing java to the community role. `sdkman-contrib`'s check refuses it instead.
- Changing what admin or vendor tokens may do.
- The `sdkman-contrib` repo itself.

## Acceptance Criteria

- [ ] Every write route refuses a role it does not explicitly admit; a token with an unknown role is refused
- [ ] Every admin and vendor status code on every route is unchanged
- [ ] A community account can log in and receives a token with role `community`
- [ ] With no community account configured, State starts normally and a community login fails with `401`
- [ ] The community role can register, update and delete candidates, subject to the existing `409` for a candidate with versions
- [ ] The community role can post, overwrite and delete versions of every candidate
- [ ] The community role can assign and remove any tag on every candidate
- [ ] No other candidate is refused to the community role, including one in a vendor's scope
- [ ] The community role is refused every `/admin/vendors` route
- [ ] Community version and tag writes are recorded in `vendor_audit` under the community account; candidate writes stay unaudited, as for the admin
- [ ] OpenAPI documents the community role's access
- [ ] All quality gates pass (`./gradlew check`)
