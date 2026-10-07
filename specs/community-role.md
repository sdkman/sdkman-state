# Community Role and Public Validation

State knows two kinds of caller today. The **admin** can do anything. A **vendor** can publish
versions and tags for the candidates in its scope, and nothing else. Neither fits the public
contribution path that replaces the archived `sdkman-db-migrations`: anyone opens a PR in
`sdkman-contrib`, a SDKMAN maintainer merges it, and a job writes it to State. That job
should not hold the admin's credential, and a vendor's fixed scope cannot cover a candidate
that has not been registered yet.

This feature adds a third role, **community**, and a way to ask State whether a contribution
would be accepted without writing anything. The community role looks after every candidate
nobody else owns: it registers, updates and deletes candidates, publishes, overwrites and
deletes their versions, and manages their tags. It never touches java, never touches a
candidate a vendor publishes, and never manages vendors.

Public validation is how `sdkman-contrib` checks a PR before a maintainer merges it. It needs
no login, so it works from a fork's PR, where GitHub withholds secrets. It answers exactly as
the real write would, as if the community role had made it, and writes nothing.

Adding a third role exposes a defect: the version and tag routes treat any role that is not
`vendor` as admin. That is fixed here, before a third role exists to slip through.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §14
([`application-end-game.md`](../../../docs/specs/application-end-game.md)). The consumer is
[`sdkman-contrib`](../../../community/sdkman-contrib/specs/community-contributions.md), which
lists what it needs from State under* What State must provide*. Settled in a grilling session
on 2026-10-07. Builds on [`jwt-authentication.md`](jwt-authentication.md) and the candidate
registry ([`candidate-registry.md`](candidate-registry.md),
[`candidate-registry-allow-list.md`](candidate-registry-allow-list.md)).*

## Behaviour

**The community role is a single account.** It logs in like the admin and vendors do and
receives a token whose role is `community`. There is one community account, held by
`sdkman-contrib`'s apply job.

**It may change any candidate that is not off limits.** A candidate is off limits when it is
`java`, or when it is in the scope of any vendor that has not been deleted. On every other
candidate, registered or not, the role can do everything the admin can do to candidates,
versions and tags. Admin-only operations outside candidates, versions and tags, such as
managing vendors, stay admin-only.

**The off-limits set is fixed at login.** A community token carries the candidates it may not
touch, as of the moment it was issued, just as a vendor token carries the candidates it may.
If an admin grants a vendor a candidate, community tokens issued afterwards are refused it,
and tokens already issued keep their old answer until they expire (10 minutes today).
Authorization stays stateless: no write reads the database to decide who may make it.

**Authorization fails closed.** A role gets only what it is explicitly granted. A token with a
role State does not know is refused by every write route. Today the version and tag routes
restrict a caller only when its role is `vendor` and treat any other role as admin; that
stops.

**Admin and vendor behaviour is unchanged.** Every status code either of them gets today, on
every route, stays the same.

**Public validation answers as the real write would.** It needs no login and never writes. It
evaluates a request as if the community role had sent it and returns the status the write
would return, with every failure listed at once, not just the first. Two kinds of request
can be validated, matching what `sdkman-contrib` applies:

- a **registration**: a candidate's identifier, name, description and website;
- a **release**: one candidate, a list of version rows, and an optional default.

A release is validated as a whole. Its default may name a version in the same release. A
release whose default names no version in the release is validated for everything else;
whether that version already exists is the caller's to check, through the existing public
read routes.

**Validation reads nothing from the database per request.** It works from the request
itself, plus the candidate registry and the vendor scopes, both of which State holds in memory
and refreshes. That makes it as cheap as a public read, so it is not throttled. Its answers
may lag a registry or vendor-scope change by up to the registry's refresh interval (five
minutes today).

## API Contract

### Community access, by route

| Route | Admin | Vendor | Community |
|---|---|---|---|
| `POST /versions` | any candidate | own candidates | any candidate not off limits |
| `DELETE /versions` | any candidate | own candidates | any candidate not off limits |
| `POST /versions/tags`, `DELETE /versions/tags` | any candidate | own candidates | any candidate not off limits |
| `POST /admin/candidates` | yes | no (401) | any candidate not off limits |
| `DELETE /admin/candidates/{candidate}` | yes | no (401) | any candidate not off limits |
| `/admin/vendors` (all) | yes | no (401) | no (401) |

A community request for an off-limits candidate is refused with `403`, naming the reason
(`java`, or that the candidate is published by its vendor). Every other rule on these routes
applies to the community role unchanged: validation, the registry check on `POST /versions`,
and the `409` that refuses to delete a candidate that still has versions.

### Public validation

Two unauthenticated routes, one per request kind. Neither writes, and both answer
`Cache-Control: no-store`, like the write routes they stand in for.

| Status | Body | When |
|---|---|---|
| `200 OK` | empty object | The community role's write would be accepted |
| `400 Bad Request` | `ValidationErrorResponse` | One or more validation failures, all of them listed |
| `403 Forbidden` | `ErrorResponse` | The candidate is off limits to the community role |
| `500 Internal Server Error` | `ErrorResponse` | The registry has never loaded, as for `POST /versions` |

A **registration** body has the fields of `POST /admin/candidates`.

A **release** body:

| Field | Required | Notes |
|---|---|---|
| `candidate` | yes | applies to every row |
| `versions` | no | rows with the fields of `POST /versions`, minus `candidate`; may be empty |
| `default` | no | a version string; becomes the `lts` tag |

A release with neither versions nor a default is a `400`. Rows are validated exactly as
`POST /versions` validates them, including the registry check, so a release for an
unregistered candidate is a `400`.

## Business Rules

1. **Off limits means java, or in a live vendor's scope.** A vendor that has been deleted owns
   nothing. Off-limits candidates are refused for every community write and every
   validation, registered or not, so the role cannot register a candidate a vendor already
   claims.
2. **The community role cannot manage vendors.** It is refused every `/admin/vendors` route.
3. **Everything else the admin may do to candidates, versions and tags, the community role
   may do to candidates that are not off limits.** That includes overwriting, deleting and
   any tag; `sdkman-contrib` uses a subset, and State does not encode it.
4. **The off-limits set is fixed when the token is issued.** A change to vendor scopes reaches
   community writes through the next login, within one token lifetime.
5. **Authorization fails closed.** Each write route names the roles it admits; any other role,
   including one State does not recognise, is refused.
6. **Community writes are attributed to the community account.** Where a vendor's write is
   recorded in the audit trail with its identity, a community write is recorded with the
   community account's.
7. **Validation never writes and needs no login.** It is evaluated as the community role,
   whoever calls it.
8. **Validation mirrors the write.** For any request, the validation status is the status the
   community role's write would get, except that a write would fail for reasons validation
   does not see (a database error, or a default naming a version outside the release that
   does not exist).
9. **Validation reports every failure at once.** A release with three bad rows lists all
   three, each identified by its position in the release.
10. **Validation reads nothing from the database per request,** and is not throttled.

## Rollout

**One release, no migration of data.** Ship it before `sdkman-contrib`'s apply goes live.
Then provision the community account's credential as a secret in `sdkman-contrib`, and nowhere
else.

The fail-closed change ships in the same release, and it is observable only to a role that
does not exist before it, so admin and vendor callers see nothing change.

**Rollback.** Redeploy the previous release. The community role and validation routes vanish,
`sdkman-contrib`'s check and apply fail loudly, and nothing already written is affected.

## Findings

- **The version and tag routes fail open.** `POST /versions`, `DELETE /versions`,
  `POST /versions/tags` and `DELETE /versions/tags` each refuse a caller only when its role is
  `vendor` and the candidate is outside its scope; any other role passes as if it were admin.
  The `/admin/vendors` routes and the candidate admin routes test for `admin` explicitly and
  are already closed. Introducing a third role without fixing the first four would grant it
  admin rights on versions and tags.
- **The candidate admin routes refuse a vendor with `401`, not `403`.** That stays as it is for
  vendors. The community role's refusal for an off-limits candidate is a `403`, because the
  caller is allowed on the route, just not for that candidate.
- **The registry is already held in memory and refreshed** (the allow-list cutover), so the
  registry check validation needs is the same one `POST /versions` makes, with no new read.
  Vendor scopes are not held that way today.

## Examples

```gherkin
Feature: Community role

  Scenario: The community role registers a new candidate
    Given "jpx" is not registered and is in no vendor's scope
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

  Scenario: java is off limits
    When the community role posts a version of "java"
    Then the response status is 403

  Scenario: A vendor's candidate is off limits
    Given a live vendor's scope includes "gradle"
    When the community role posts a version of "gradle"
    Then the response status is 403
      And the message says "gradle" is published by its vendor

  Scenario: A deleted vendor owns nothing
    Given a deleted vendor's scope includes "jbang"
      And no live vendor's scope includes "jbang"
    When the community role posts a version of "jbang"
    Then the version is accepted

  Scenario: A new vendor scope applies from the next login
    Given a community token issued before a vendor was granted "jpx"
    Then that token may still post versions of "jpx" until it expires
    When the community role logs in again
    Then posting a version of "jpx" returns 403

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

Feature: Public validation

  Scenario: A valid release validates without writing
    Given "jpx" is registered
    When anyone validates a release of "jpx" with versions 1.2.0 for LINUX_X64 and MAC_ARM64 and default 1.2.0
    Then the response status is 200
      And "jpx 1.2.0" does not exist

  Scenario: Every failure is reported at once
    When anyone validates a release with three invalid rows
    Then the response status is 400
      And all three failures are listed, each with its row position

  Scenario: A release for an unregistered candidate is refused
    Given "jpx" is not registered
    When anyone validates a release of "jpx"
    Then the response status is 400

  Scenario: The default may name a version in the same release
    When anyone validates a release of "jpx" adding 1.3.0 with default 1.3.0
    Then the response status is 200

  Scenario: An off-limits candidate is refused
    When anyone validates a release of "java"
    Then the response status is 403

  Scenario: An empty release is refused
    When anyone validates a release of "jpx" with no versions and no default
    Then the response status is 400

  Scenario: A registration validates without writing
    Given "jpx" is not registered
    When anyone validates a registration of "jpx"
    Then the response status is 200
      And "jpx" is still not registered

  Scenario: Validation needs no login
    When a caller with no token validates a release
    Then it is answered, not refused with 401
```

## Out of Scope

- Validating deletes or tag changes. `sdkman-contrib` sends neither.
- Checking that a default outside the release exists. The caller uses the public read routes.
- More than one community account, or community scopes narrower than "not off limits".
- Changing what admin or vendor tokens may do.
- Throttling validation.
- The `sdkman-contrib` repo itself.

## Acceptance Criteria

- [ ] A community account can log in and receives a token with role `community`
- [ ] The community role can register, update and delete candidates that are not off limits, subject to the existing `409` for a candidate with versions
- [ ] The community role can post, overwrite and delete versions of candidates that are not off limits
- [ ] The community role can assign and remove any tag on candidates that are not off limits
- [ ] Every community write for `java`, or for a candidate in a live vendor's scope, is refused with `403` naming the reason
- [ ] A deleted vendor's scope does not make a candidate off limits
- [ ] The off-limits set is fixed at login: a scope change applies to tokens issued after it
- [ ] The community role is refused every `/admin/vendors` route
- [ ] Every write route refuses a role it does not explicitly admit; a token with an unknown role is refused
- [ ] Every admin and vendor status code on every route is unchanged
- [ ] Community writes are recorded in the audit trail under the community account
- [ ] Registration and release validation need no login, never write, and answer `no-store`
- [ ] Validation returns the status the community role's write would get, with every failure listed and each row failure identified by position
- [ ] A release's default may name a version in the same release
- [ ] Validation performs no database read per request and is not throttled
- [ ] OpenAPI documents the community role's access and both validation routes
- [ ] All quality gates pass (`./gradlew check`)
