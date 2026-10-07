# Public Validation

`sdkman-contrib` accepts candidate registrations and releases as PRs. Before a maintainer
merges one, its check has to know whether State would accept it. Otherwise a bad request is
only discovered after merge, where it fails the apply and blocks every request queued behind
it. Most of those PRs come from forks, and GitHub withholds secrets from fork PRs, so the
check cannot log in.

This feature lets anyone ask State whether a registration or a release would be accepted,
without logging in and without writing anything.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §14
([`application-end-game.md`](../../../docs/specs/application-end-game.md)). The consumer is
[`sdkman-contrib`](../../../community/sdkman-contrib/specs/community-contributions.md)'s PR
check. Settled in a grilling session on 2026-10-07. "Off limits" is pinned in the
[glossary](../../../docs/glossary.md); [`community-role.md`](community-role.md) applies the
same rule to writes, independently.*

## Behaviour

**Validation needs no login and never writes.** Anyone may call it, with or without a token,
and a token, if sent, changes nothing.

**It answers as the write would.** It applies the same rules the write routes apply to the
same fields: version syntax, platform ids, description and website rules, and the registry
check on versions. It returns the status the write would return, listing every failure at
once rather than stopping at the first.

**Off-limits candidates are refused.** A request for a candidate that is off limits (`java`,
or in a live vendor's scope) is refused with `403`, naming the reason. These candidates are
not open to public contribution, so a request for one can never succeed whatever its content.

**Two kinds of request can be validated,** matching what `sdkman-contrib` applies:

- a **registration**: a candidate's identifier, name, description and website;
- a **release**: one candidate, a list of version rows, and an optional default.

**A release is validated as a whole.** Every row is checked, and so is the default. The
default may name a version in the same release. A default naming no version in the release
is validated for everything else; whether that version already exists is the caller's to
check, through the existing public read routes.

**Validation reads nothing from the database per request.** It works from the request itself,
plus the candidate registry and the vendor scopes, which State holds in memory and refreshes.
That makes it as cheap as a public read, so it is not throttled. Its answers may lag a
registry or vendor-scope change by up to the registry's refresh interval (five minutes
today).

## API Contract

Two routes, one per request kind. Neither needs authentication or writes anything, and both
answer `Cache-Control: no-store`, since an answer depends on the registry and vendor scopes
at that moment.

| Status | Body | When |
|---|---|---|
| `200 OK` | empty object | The request would be accepted |
| `400 Bad Request` | `ValidationErrorResponse` | One or more validation failures, all of them listed |
| `403 Forbidden` | `ErrorResponse` | The candidate is off limits |
| `500 Internal Server Error` | `ErrorResponse` | The registry has never loaded, as for `POST /versions` |

A **registration** body has the fields of `POST /admin/candidates`.

A **release** body:

| Field | Required | Notes |
|---|---|---|
| `candidate` | yes | applies to every row |
| `versions` | no | rows with the fields of `POST /versions`, minus `candidate`; may be empty |
| `default` | no | a version string; would become the `lts` tag |

A release with neither versions nor a default is a `400`. A release for an unregistered
candidate is a `400`, as `POST /versions` would answer.

## Business Rules

1. **Validation never writes and needs no login.**
2. **It applies the write routes' own rules.** There is one definition of what is valid, and
   validation and the writes both use it, so they cannot drift.
3. **Off-limits candidates are refused with `403`.** A deleted vendor owns nothing.
4. **Every failure is reported at once.** A release with three bad rows lists all three, each
   identified by its position in the release.
5. **A release's default may name a version in the same release.** A default naming any other
   version is not checked for existence.
6. **It reads nothing from the database per request,** and is not throttled.
7. **A `200` is a prediction, not a promise.** The write can still fail for reasons
   validation does not see: a database error, a registry or vendor-scope change in between, or
   a default outside the release that does not exist.

## Rollout

**One release, no data migration,** ahead of `sdkman-contrib`'s PR check going live. It does
not need [`community-role.md`](community-role.md) to ship first, or at all, to answer
correctly.

**Rollback.** Redeploy the previous release. The routes vanish and `sdkman-contrib`'s check
fails loudly; nothing is written either way.

## Findings

- **The registry is already held in memory and refreshed** (the allow-list cutover), so the
  registry check validation needs is the one `POST /versions` already makes, with no new
  read. Vendor scopes are not held that way today.
- **Today's validation stops at the request.** `POST /versions` validates one row per call,
  and nothing validates several rows plus a default together. The release shape is new.

## Examples

```gherkin
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

  Scenario: java is refused
    When anyone validates a release of "java"
    Then the response status is 403

  Scenario: A vendor's candidate is refused
    Given a live vendor's scope includes "gradle"
    When anyone validates a release of "gradle"
    Then the response status is 403
      And the message says "gradle" is published by its vendor

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
- Throttling.
- Any change to the write routes.

## Acceptance Criteria

- [ ] Registration and release validation need no login, never write, and answer `no-store`
- [ ] Validation applies the write routes' own rules, from one shared definition
- [ ] Every failure is listed, each row failure identified by its position
- [ ] Off-limits candidates are refused with `403` naming the reason; a deleted vendor owns nothing
- [ ] A release's default may name a version in the same release
- [ ] A release with neither versions nor a default, or for an unregistered candidate, is a `400`
- [ ] A never-loaded registry answers `500`, as for `POST /versions`
- [ ] Validation performs no database read per request and is not throttled
- [ ] OpenAPI documents both routes
- [ ] All quality gates pass (`./gradlew check`)
