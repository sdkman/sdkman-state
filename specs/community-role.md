# Community Role

State knows two kinds of caller today. The **admin** can do anything. A **vendor** can publish
versions and tags for the candidates in its scope, and nothing else. Neither fits the public
contribution path that replaces the archived `sdkman-db-migrations`: anyone opens a PR in
`sdkman-contrib`, a SDKMAN maintainer merges it, and a job writes it to State. That job
should not hold the admin's credential, and a vendor cannot register a candidate at all, so a
vendor login could never add a new one.

This feature adds a third role, **community**. It looks after every candidate except
java: it registers, updates and deletes candidates, publishes, overwrites and deletes their
versions, and manages their tags. It never touches java, which DISCO owns, and never manages
vendors.

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

**It may change every candidate except java.** On every other candidate, registered or not,
the role can do everything the admin can do to candidates, versions and tags. Its scope is
that fixed rule and nothing else: it does not depend on vendor scopes or any other data, so
it cannot fail or go stale. Admin-only operations outside candidates, versions and tags, such
as managing vendors, stay admin-only.

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
| `POST /versions` | any candidate | own candidates | any candidate except java |
| `DELETE /versions` | any candidate | own candidates | any candidate except java |
| `POST /versions/tags`, `DELETE /versions/tags` | any candidate | own candidates | any candidate except java |
| `POST /admin/candidates` | yes | no (401) | any candidate except java |
| `DELETE /admin/candidates/{candidate}` | yes | no (401) | any candidate except java |
| `/admin/vendors` (all) | yes | no (401) | no (401) |

A community request for java is refused with `403`, naming the reason. Validation, including
the registry check, runs first and answers `400` as today; the java refusal runs where the
vendor scope check runs today, so no vendor's answer changes. On `POST /admin/candidates` and
`DELETE /admin/candidates/{candidate}`, the java refusal precedes the existence check. Every other rule on these routes
applies to the community role unchanged: validation, the registry check on `POST /versions`,
and the `409` that refuses to delete a candidate that still has versions.

## Business Rules

1. **Authorization fails closed.** Each write route names the roles it admits; any other role,
   including one State does not recognise, is refused. This is the precondition for the rest:
   without it, the community role would pass the version and tag routes as admin. An unknown
   role gets `403` on the version and tag routes and `401` on the `/admin` routes, the same as
   each route already gives a role it doesn't allow.
2. **java is refused.** Every community write for java is refused with `403`. No other
   candidate is refused, whatever any vendor's scope says.
3. **The community role cannot manage vendors.** It is refused every `/admin/vendors` route.
4. **Everything else the admin may do to candidates, versions and tags, the community role
   may do to every candidate except java.** That includes overwriting, deleting and
   any tag; `sdkman-contrib` uses a subset, and State does not encode it.
5. **Community writes are attributed to the community account.** Where a vendor's write is
   recorded in the audit trail with its identity, a community write is recorded with the
   community account's. The community token's `vendor_id` is a fixed, documented sentinel UUID,
   distinct from the admin's nil UUID, and `email` is the community account's email, so a
   `vendor_audit` row can be attributed to the community by either column.

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
- **The candidate admin routes refuse a vendor with `401`, not `403`.** That stays as it is for
  vendors. The community role's refusal for java is a `403`, because the
  caller is allowed on the route, just not for that candidate.

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

  Scenario: java is refused
    When the community role posts a version of "java"
    Then the response status is 403

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
- More than one community account, or a community scope narrower than "every candidate except java".
- Changing what admin or vendor tokens may do.
- The `sdkman-contrib` repo itself.

## Acceptance Criteria

- [ ] Every write route refuses a role it does not explicitly admit; a token with an unknown role is refused
- [ ] Every admin and vendor status code on every route is unchanged
- [ ] A community account can log in and receives a token with role `community`
- [ ] The community role can register, update and delete candidates except java, subject to the existing `409` for a candidate with versions
- [ ] The community role can post, overwrite and delete versions of every candidate except java
- [ ] The community role can assign and remove any tag on every candidate except java
- [ ] Every community write for `java` is refused with `403` naming the reason
- [ ] No other candidate is refused to the community role, including one in a vendor's scope
- [ ] The community role is refused every `/admin/vendors` route
- [ ] Community version and tag writes are recorded in `vendor_audit` under the community account; candidate writes stay unaudited, as for the admin
- [ ] OpenAPI documents the community role's access
- [ ] All quality gates pass (`./gradlew check`)
