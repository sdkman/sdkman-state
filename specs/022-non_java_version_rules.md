---
id: "022"
title: "Non-java Version Rules"
status: draft
created: 2026-10-24
depends_on: ["011", "017", "019"]
---

# Non-java Version Rules

`sdkman-contrib` is deliberately dumb. It turns a merged YAML request into `POST /versions`
calls and fails fast on whatever State refuses; it holds no rule of its own about what a
version may look like or how its default moves. Its PR check asks State the same question
through the validation twin, `POST /validate/versions`. That only works if State actually
enforces the rules, and for non-java candidates today it enforces almost none: a version is
refused only when it is blank, a version can hold a `UNIVERSAL` row beside platform rows, a
`stable` tag can land where the default cascade never reads it, and any tag name is accepted.

This feature gives every non-java version write four rules: a version grammar, one platform
shape per version, only the `stable` and `latest` tags, and a `stable` tag that always
agrees with the candidate's default. They bind every caller, and the validation twin answers
exactly as the write does. It also retires the legacy `lts` tag for non-java candidates.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §14
([`application-end-game.md`](../../../docs/specs/application-end-game.md)). The consumer is
[`sdkman-contrib`](../../../community/sdkman-contrib/specs/001-community-contributions.md).
Settled in a review of that spec on 2026-10-24. Builds on
[`019-default_tag_cascade.md`](019-default_tag_cascade.md),
[`011-assign_tag_to_version.md`](011-assign_tag_to_version.md) and
[`017-public_validation.md`](017-public_validation.md). Where earlier specs conflict, this one wins.*

## Behaviour

**The rules bind every caller.** Admin, vendor and community writes are judged the same way.
A write that breaks a rule is refused with `400`, whoever sends it.

**java is unchanged.** java keeps its own grammar and tagging, owned by
[`021-java_supersession.md`](021-java_supersession.md). Nothing here applies to a java row.

**A version must look like a version.** A non-java version is refused unless it matches
`^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$`: it starts with a letter or digit, holds only letters,
digits, `.`, `_` and `-`, and is at most 50 characters long. `1.2.0`, `3.2.9.Final`,
`2.0.0-RC15` and `5.23.0.0_2` pass; `1.2.0 LTS`, `-1.0` and `1.0/beta` fail.

**One version has one platform shape.** A version is either one `UNIVERSAL` row or a set of
platform rows, never both. A `UNIVERSAL` row is refused for a version that already has a
platform row, and a platform row is refused for a version that already has a `UNIVERSAL` row.
Overwriting a row that already exists is unaffected.

**Only `stable` and `latest`, and they are independent.** `stable` marks the chosen default and
`latest` the newest release. Any other tag on a non-java row is refused, `lts` included. A row
may carry `stable`, `latest`, both, or neither, and setting one never removes the other. So
when the newest release is also the default, one row carries both. The CLI candidates rely on
this: promoting a beta tags its row `stable`, and it keeps `latest` until a newer beta takes it,
so the beta channel always has an answer.

**`lts` is retired for non-java candidates.** It is a legacy remnant from Mongo. The default
cascade reads `stable` only: `stable` at `UNIVERSAL`, then `stable` at `LINUX_X64`. A candidate
with neither has no `default`.

**`stable` stays per platform, and never disagrees with itself.** As today, each platform
holds its own `stable`, and tagging a row `stable` moves the tag off whichever version held it
on that platform. On top of that:

- Tagging a platform row `stable` also clears `stable` from any other version's `UNIVERSAL`
  row.
- Tagging a `UNIVERSAL` row `stable` also clears `stable` from every other version's platform
  rows.

So the version the default cascade picks is always the version every platform's `stable`
lookup answers, wherever one exists.

*(Assumption, planning 2026-11-21: an "other version" is a row of the same candidate and
distribution whose version differs. The clear never crosses a distribution and never touches
the tagged version's own rows.)*

**Both ways of tagging obey the same rules.** The `tags` field of `POST /versions` and the tag
route, `POST /versions/tags`, move and refuse tags identically.

*(Assumption, planning 2026-11-21: on a non-java row the `tags` field sets each tag it lists,
as the tag route sets one, and removes none. Its replace semantics end there: a tag the row
holds but the field leaves out stays, and an empty list changes nothing. `DELETE /versions/tags`
still removes a tag.)*

**The twin answers as the write does.** `POST /validate/versions` applies every rule here
against the State it reads at that moment, and answers exactly as `POST /versions` would.

*(Assumption, planning 2026-11-21: the platform-shape rule reads the version's stored rows, so
the twin now reads the database per request, which rule 4 of
[`017-public_validation.md`](017-public_validation.md) ruled out. A failed read answers `500` on
both routes. Non-java writes are not serialised: each is judged against State as it reads it,
and two concurrent writes for one candidate are not guarded against each other.)*

## API Contract

No new routes and no new request fields. New `400` answers:

| Route | Refused when |
|---|---|
| `POST /versions`, `POST /validate/versions` | a non-java version breaks the grammar |
| `POST /versions`, `POST /validate/versions` | the row would give its version both a `UNIVERSAL` row and a platform row |
| `POST /versions`, `POST /validate/versions`, `POST /versions/tags` | a non-java tag other than `stable` or `latest` |

## Business Rules

1. **Grammar.** A non-java version matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$`, or the write
   is refused with `400`.
2. **One platform shape.** A version never holds a `UNIVERSAL` row and a platform row at once.
3. **Per-platform `stable`, cleared across shapes.** Moving `stable` onto a platform row clears
   it from other versions' `UNIVERSAL` rows; moving it onto a `UNIVERSAL` row clears it from
   other versions' platform rows. No other tag is touched.
4. **Two independent tags.** A non-java row carries only `stable` and `latest`, in any
   combination. Setting one leaves the other on that row as it was.
5. **No `lts`.** The default cascade for non-java candidates reads `stable` only, `UNIVERSAL`
   before `LINUX_X64`.
6. **One answer.** The `tags` field, the tag route and the validation twin apply rules 3 and 4
   identically.

## Rollout

**`vendor-release` changes first.** It marks a vendor's default with `lts` today. It must send
`stable` instead before this release, or its default publishes are refused. Because the cascade
already reads `stable` before `lts`, that change is safe to ship on its own.

**One release, with one data fix.** The 2026-10-10 survey of `state.sdkman.io` found every
non-java candidate tagged `lts`, five also tagged `latest`, and none tagged `stable`. The
release retags every non-java `lts` as `stable`, so no live default moves on deploy. A row that
also carries `latest` keeps it.
Apart from that fix, the rules judge new writes only. Every non-java version in the phase 1
snapshot matches the grammar.

*(Assumption, planning 2026-11-21: `stable` has been written since the survey, so a scope may
already hold it. The retag skips an `lts` whose candidate, distribution and platform already
hold `stable`. It also skips a `UNIVERSAL` `lts` with no distribution when its candidate holds
`stable` at `LINUX_X64` with no distribution, since retagging it would move that default. A
skipped `lts` stays as it is, and the cascade no longer reads it.)*

**Rollback.** Redeploy the previous release. The rules lift, and nothing written under them
changes. The retagged rows keep `stable`, which the previous release's cascade reads first,
so no default moves.

## Consumers

- **`vendor-release`.** It changes first, as §Rollout states.
- **The control plane's local stack.** `seed/seed.sh` tags each non-java default `lts`, tags
  `contrib 0.1.1` `lts` so that `stable` must beat it, and posts groovy `9.9.9+build`, which the
  grammar refuses. Each of those posts fails the day this ships. One control-plane change, landed
  with this build, tags those defaults `stable`, drops the synthetic `lts` and drops
  `9.9.9+build`. The `+` checks that read `9.9.9+build`, Insomnia folder `08` and
  `compose/verify-nginx-front.sh`, read the seeded java `21.0.13.0+9-tem` instead, since only a
  java version can still carry a `+`. The Insomnia suite, its spec and the glossary stop
  describing an `lts` default for a non-java candidate.

*(Assumption, planning 2026-11-21: this spec named no control-plane change. This one keeps the
workspace verification gate green. `test/integration.sh` sits outside that gate, but its groovy
default check reads `stable` too, so none of its checks newly fails.)*

## Examples

```gherkin
Feature: Non-java version rules

  Scenario: A malformed version is refused
    When any caller posts "jpx" version "1.2.0 LTS"
    Then the response status is 400

  Scenario: A UNIVERSAL row beside platform rows is refused
    Given "jpx 1.2.0" exists for LINUX_X64
    When any caller posts "jpx 1.2.0" for UNIVERSAL
    Then the response status is 400

  Scenario: A platform stable clears an older UNIVERSAL stable
    Given "jpx 1.1.0" for UNIVERSAL is tagged "stable"
    When "jpx 1.2.0" for LINUX_X64 is posted with tags ["stable"]
    Then "jpx 1.1.0" carries no "stable" tag
      And "jpx"'s default is 1.2.0

  Scenario: A UNIVERSAL stable clears older platform stables
    Given "jpx 1.1.0" for LINUX_X64 and MAC_ARM64 is tagged "stable"
    When "jpx 1.2.0" for UNIVERSAL is posted with tags ["stable"]
    Then "jpx 1.1.0" carries no "stable" tag on any platform
      And "jpx"'s default is 1.2.0

  Scenario: lts is refused for a non-java candidate
    When any caller posts "jpx 1.2.0" for LINUX_X64 with tags ["lts"]
    Then the response status is 400

  Scenario: stable and latest share a row
    Given "jpx 1.2.0" for LINUX_X64 is tagged "latest"
    When it is tagged "stable"
    Then it carries "stable" and "latest"

  Scenario: A new latest leaves stable alone
    Given "jpx 1.2.0" for LINUX_X64 is tagged "stable" and "latest"
    When "jpx 1.3.0" for LINUX_X64 is posted with tags ["latest"]
    Then "jpx 1.3.0" carries "latest"
      And "jpx 1.2.0" carries "stable" only

  Scenario: Live lts defaults survive the release
    Given "gradle 8.14" for UNIVERSAL is tagged "lts"
    When this release is deployed
    Then "gradle 8.14" for UNIVERSAL is tagged "stable"
      And "gradle"'s default is 8.14

  Scenario: The twin agrees with the write
    When "jpx 1.2.0 LTS" is sent to POST /validate/versions
    Then the response status is 400
```

## Out of Scope

- java versions and tags.
- Rewriting rows already in State that break a rule, apart from the `lts` data fix.
- Refusing a `stable` the default cascade never reads.
- The `vendor-release` change itself.

## Acceptance Criteria

- [ ] A non-java version not matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$` is refused with `400` for every caller
- [ ] A `UNIVERSAL` row is refused for a version with a platform row, and the reverse
- [ ] Tagging a platform row `stable` clears `stable` from other versions' `UNIVERSAL` rows; tagging a `UNIVERSAL` row `stable` clears it from other versions' platform rows
- [ ] A non-java tag other than `stable` or `latest` is refused with `400`
- [ ] A row accepts `stable` and `latest` together, and setting one leaves the other in place
- [ ] The default cascade for non-java candidates reads `stable` only
- [ ] After deploy, every non-java row that was tagged `lts` is tagged `stable`, and no non-java default has changed
- [ ] The `tags` field and the tag route move and refuse tags identically
- [ ] `POST /validate/versions` answers every rule exactly as `POST /versions` does
- [ ] java writes behave as before
- [ ] OpenAPI documents the new `400` answers
- [ ] All quality gates pass (`./gradlew check`)
