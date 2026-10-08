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
check. Settled in grilling sessions on 2026-10-07 and 2026-10-08.*

## Behaviour

**Each validation route is the twin of one write route.**

| Validation route | Twin |
|---|---|
| `POST /validate/versions` | `POST /versions` |
| `POST /validate/candidates` | `POST /admin/candidates` |

A twin takes exactly the body its write route takes. It answers `204` where the write route
would succeed (the candidate route's `201` or `200` with a body becomes `204`), and otherwise
exactly as the write route would, without writing or a login.

A validation route predicts whether the body is valid, which is the same for every caller. It
never checks who may write it; that is authorization, on the write route.

**There is one validation, not two.** Validation is the whole answer, not just the rules: the
`500` for a registry that has never loaded, parsing the body, the rule checks, and building
the `400` response body. A write route and its twin call one function that returns that full
answer, and the two route handlers differ only in authentication and the write. So for any
body a twin's `400` is identical to its write route's `400`, and they cannot drift.

**Validation needs no login, and authentication never depends on a flag.** Every route under
`/validate/` is open to anyone, with or without a token, and a token, if sent, changes
nothing. Every write route stays authenticated exactly as today. No route is sometimes open
and sometimes not.

**One body, one answer.** A release is one call to `POST /validate/versions` per row, that is
per version and platform: 20 versions on three platforms is 60 calls, posting exactly the
bodies the apply will later post. Marking the default is not an extra call: the default
version's rows carry `tags: ["lts"]`, a field the validation already checks. Validation is cheap enough
for that: it reads nothing from the database per request, only the request and the candidate
registry State holds in memory and refreshes, so it is not throttled. Its answers may lag a
registry change by up to the registry's refresh interval (five minutes today).

**Body size is capped.** Because the routes are open to anyone, a validation route refuses a
body larger than a fixed limit with `413`, before reading it. A real body is a few hundred
bytes, so the limit can be small; its exact value is left to planning.

## API Contract

Both routes answer `Cache-Control: no-store`, since an answer depends on the registry at
that moment.

| Status | Body | When |
|---|---|---|
| `204 No Content` | | The write would be accepted |
| `400 Bad Request` | as the write route | Validation fails; identical to the write route's answer for the same body |
| `413 Content Too Large` | | The body exceeds the size limit |
| `500 Internal Server Error` | `ErrorResponse` | `POST /validate/versions` only: the registry has never loaded, as for `POST /versions` |

`401` never applies.

## Business Rules

1. **Each validation route takes its write twin's body, unchanged.** No new request shape.
2. **Validation is defined once.** Validation covers the registry-readiness `500`, parsing,
   the rule checks and the `400` response body. The write route and its twin call one function
   that returns that full answer, and give the same `400` for the same body.
3. **Validation routes never write and need no login.** Write routes keep their
   authentication unchanged. No route's authentication depends on the request.
4. **Validation reads nothing from the database per request,** and is not throttled.
5. **Bodies over a fixed size limit are refused with `413`,** unread.
6. **A `204` is a prediction, not a promise.** The write can still fail for reasons
   validation does not see: authorization, a database error, or a registry change in between.

## Rollout

**One release, no data migration,** ahead of `sdkman-contrib`'s PR check going live. It does
not need [`community-role.md`](community-role.md) to ship first, or at all, to answer
correctly.

**Rollback.** Redeploy the previous release. The routes vanish and answer `404`, which fails
`sdkman-contrib`'s check, since it passes only on `204`; nothing is written either way.

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

  Scenario Outline: A twin answers exactly as its write route
    Given a <route> body that the write route rejects with 400 because <case>
    When anyone posts the same body to its validation twin
    Then the response status is 400
      And the body is identical to the write route's

    Examples: every existing 400 case of POST /versions and POST /admin/candidates
      | route                 | case                          |
      | POST /versions        | <each existing 400 case>      |
      | POST /admin/candidates | <each existing 400 case>     |

  Scenario: A version of an unregistered candidate is refused
    Given "jpx" is not registered
    When anyone validates a version of "jpx"
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
- [ ] Validation, including the `400` response body and the registry-readiness `500`, is one function shared by each write route and its twin
- [ ] Validation routes need no login, never write, answer `204` on success and `no-store` always
- [ ] Every write route still requires authentication as today
- [ ] A body over the size limit is refused with `413` on both validation routes
- [ ] A never-loaded registry answers `500` on `POST /validate/versions`, as on `POST /versions`
- [ ] Validation performs no database read per request and is not throttled
- [ ] OpenAPI documents both routes
- [ ] All quality gates pass (`./gradlew check`)
