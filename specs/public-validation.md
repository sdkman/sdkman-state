# Public Validation

`sdkman-contrib` accepts candidate registrations and releases as PRs. Before a maintainer
merges one, its check has to know whether State would accept it. Otherwise a bad request is
only discovered after merge, where it fails the apply and blocks every request queued behind
it. Those PRs come from third-party forks, and GitHub withholds secrets from fork PRs, so the
check has no credentials and cannot log in.

This feature lets anyone ask State whether a write would be accepted, without logging in and
without writing anything. It adds no new request shape: each validation route is the exact
twin of one write route, taking the same body and running the same validation.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §14
([`application-end-game.md`](../../../docs/specs/application-end-game.md)). The consumer is
[`sdkman-contrib`](../../../community/sdkman-contrib/specs/community-contributions.md)'s PR
check. Settled in grilling sessions on 2026-10-07 and 2026-10-08.
[`community-role.md`](community-role.md) refuses java to community writes, independently.*

## Behaviour

**Each validation route is the twin of one write route.**

| Validation route | Twin |
|---|---|
| `POST /validate/versions` | `POST /versions` |
| `POST /validate/candidates` | `POST /admin/candidates` |

A twin takes exactly the body its write route takes, and answers exactly as the write route
would, except that it writes nothing and needs no login.

**There is one validation, not two.** A write route is its twin's validation, followed by
authorization and the write itself. The rules are defined once and both routes run them, so
for any body a twin's `400` is identical to its write route's `400`, and they cannot drift.

**Validation needs no login, and authentication never depends on a flag.** Every route under
`/validate/` is open to anyone, with or without a token, and a token, if sent, changes
nothing. Every write route stays authenticated exactly as today. No route is sometimes open
and sometimes not.

**java is refused.** After validation passes, a request for java is refused with `403`,
naming the reason, in the same position the community role's java refusal takes on the write
route. DISCO owns java, and it is not open to public contribution. No other candidate is
refused.

**One body, one answer.** A release of 20 versions is 20 calls to `POST /validate/versions`,
one per row, posting exactly the bodies the apply will later post. Validation is cheap enough
for that: it reads nothing from the database per request, only the request and the candidate
registry State holds in memory and refreshes, so it is not throttled. Its answers may lag a
registry change by up to the registry's refresh interval (five minutes today).

## API Contract

Both routes answer `Cache-Control: no-store`, since an answer depends on the registry at
that moment.

| Status | Body | When |
|---|---|---|
| `204 No Content` | | The write would be accepted |
| `400 Bad Request` | as the write route | Validation fails; identical to the write route's answer for the same body |
| `403 Forbidden` | `ErrorResponse` | The body is valid, and the candidate is java |
| `500 Internal Server Error` | `ErrorResponse` | `POST /validate/versions` only: the registry has never loaded, as for `POST /versions` |

`401` never applies.

## Business Rules

1. **Each validation route takes its write twin's body, unchanged.** No new request shape.
2. **Validation is defined once.** The write route and its twin run the same validation, and
   give the same `400` for the same body.
3. **Validation routes never write and need no login.** Write routes keep their
   authentication unchanged. No route's authentication depends on the request.
4. **java is refused with `403` after validation passes.** No other candidate is.
5. **Validation reads nothing from the database per request,** and is not throttled.
6. **A `204` is a prediction, not a promise.** The write can still fail for reasons
   validation does not see: authorization, a database error, or a registry change in between.

## Rollout

**One release, no data migration,** ahead of `sdkman-contrib`'s PR check going live. It does
not need [`community-role.md`](community-role.md) to ship first, or at all, to answer
correctly.

**Rollback.** Redeploy the previous release. The routes vanish and `sdkman-contrib`'s check
fails loudly; nothing is written either way.

## Findings

- **The registry is already held in memory and refreshed** (the allow-list cutover), so the
  registry check `POST /validate/versions` needs is the one `POST /versions` already makes,
  with no new read.
- **Validation and the write are already separable on `POST /versions`.** It validates the
  whole body, answering `400`, before it authorizes or writes. `POST /admin/candidates`
  authorizes first, so on that route the twin's answer must be the validation it runs after
  authorization.

## Examples

```gherkin
Feature: Public validation

  Scenario: A valid version validates without writing
    Given "jpx" is registered
    When anyone validates the POST /versions body for "jpx 1.2.0" on LINUX_X64
    Then the response status is 204
      And "jpx 1.2.0" does not exist

  Scenario: A twin answers exactly as its write route
    Given any POST /versions body that the write route rejects with 400
    When anyone posts the same body to POST /validate/versions
    Then the response status is 400
      And the body is identical to the write route's

  Scenario: A version of an unregistered candidate is refused
    Given "jpx" is not registered
    When anyone validates a version of "jpx"
    Then the response status is 400

  Scenario: java is refused
    When anyone validates a valid version of "java"
    Then the response status is 403

  Scenario: An invalid java body is a 400, not a 403
    When anyone validates an invalid version body of "java"
    Then the response status is 400

  Scenario: A registration validates without writing
    Given "jpx" is not registered
    When anyone validates the POST /admin/candidates body for "jpx"
    Then the response status is 204
      And "jpx" is still not registered

  Scenario: Validation needs no login
    When a caller with no token validates a version
    Then it is answered, not refused with 401

  Scenario: Write routes still need a login
    When a caller with no token posts to POST /versions
    Then the response status is 401
```

## Out of Scope

- Validating deletes or tag changes. `sdkman-contrib` sends neither through validation.
- Validating several rows, or a default, in one call.
- Throttling.
- Any change to the write routes' contracts or authentication.

## Acceptance Criteria

- [ ] `POST /validate/versions` and `POST /validate/candidates` take exactly the bodies of `POST /versions` and `POST /admin/candidates`
- [ ] For the same body, each validation route's `400` is identical to its write route's, proven across the write routes' existing validation cases
- [ ] Validation logic exists once, shared by each write route and its twin
- [ ] Validation routes need no login, never write, answer `204` on success and `no-store` always
- [ ] Every write route still requires authentication as today
- [ ] A valid body for java is refused with `403`; an invalid one is a `400`
- [ ] A never-loaded registry answers `500` on `POST /validate/versions`, as on `POST /versions`
- [ ] Validation performs no database read per request and is not throttled
- [ ] OpenAPI documents both routes
- [ ] All quality gates pass (`./gradlew check`)
