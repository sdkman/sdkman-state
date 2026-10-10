# Default Tag Cascade

The derived `default` on `GET /candidates` is the version carrying the `lts` tag. For java that is
right: `lts` is a JEP 322 concept. For every other candidate it is a borrowed word. Most tools have
no long-term-support line, and the tag their maintainers would naturally reach for is `stable`.

This feature lets a non-java candidate's default come from a `stable` tag, falling back to `lts`
when there is none. Nothing else about the derived default changes.

*Amends rules 4, 5 and 7 of [`candidate-registry.md`](candidate-registry.md) and its default
scenarios. Java's default is untouched.*

## Behaviour

A non-java candidate's `default` is resolved by trying, in order, until one matches:

1. the version tagged `stable` at `UNIVERSAL`
2. the version tagged `stable` at `LINUX_X64`
3. the version tagged `lts` at `UNIVERSAL`
4. the version tagged `lts` at `LINUX_X64`

The **tag decides first, then the platform**. A `stable` tag on either platform beats `lts` on any
platform. A candidate matching none of the four has no `default`, and the field is absent, as today.

Adopting `stable` is the candidate owner's opt-in. Whoever publishes a candidate's versions, its
vendor through `POST /versions` or the community through `sdkman-contrib`, chooses which tag they
send. Once a candidate carries `stable`, its `lts` tag no longer affects its default. Nothing
enforces that a candidate uses only one of the two.

## Business Rules

These replace the corresponding rules in [`candidate-registry.md`](candidate-registry.md); rules 6
and 8 there stand unchanged.

4. **`default` is derived, never stored.** It is read from `version_tags` on every request. Nothing
   writes a default onto a candidate row and there is no endpoint to set one. Moving a default means
   moving the `stable` tag, or the `lts` tag for a candidate that has no `stable`.
5. **`default` cascades by tag, then by platform:** `stable` before `lts`, and within each tag
   `UNIVERSAL` before `LINUX_X64`. No other tag and no other platform is consulted. The resolution
   is restricted to those four combinations, not merely ordered by them. `latest`, a major-version
   tag such as `9`, or a `stable` sitting only on `MAC_ARM64` never produces a default.
7. **`default` does not filter on `visible`.** Unchanged in substance. The `sdk list` header and
   `sdk default` both read this one derived value, so they agree. The derived value equals what
   `GET /versions/{c}/tags/{tag}` returns for whichever tag and platform won the cascade.

## Findings

- **No live candidate carries `stable` today.** A survey of `state.sdkman.io` on 2026-10-10 found
  all 77 non-java candidates tagged `lts`, five also tagged `latest`, and none tagged `stable`.
  Shipping this changes no live default.
- **`latest` is deliberately excluded.** It means newest, not chosen, and it already disagrees with
  the default on live: `kotlintoolchain` carries `latest` on 0.12.1 and `lts` on 0.13.0, whose
  default is 0.13.0.
- **`lts` is written by existing publishers.** Vendors send it on `POST /versions`, and
  `vendor-release` (winding down) writes it on default publishes. Neither needs to change. They
  keep moving the default for every candidate that has not opted into `stable`.
- **The traps recorded in [`candidate-registry.md`](candidate-registry.md) §Findings still apply,
  now per tag.** Resolution stays one query for the whole registry, the cut is per candidate, and
  the platform and tag predicates must exclude, not only order. A preference ordering without the
  exclusion would admit `latest` or a `MAC_ARM64`-only `stable`.

## Examples

These replace the four `lts` default scenarios in
[`candidate-registry.md`](candidate-registry.md) §Examples. The java scenario stays as it is.

```gherkin
Feature: Derived candidate default

  Scenario: A candidate's default comes from its stable tag
    Given the candidate "gradle" is registered
      And version "8.14" of "gradle" on "UNIVERSAL" is tagged "stable"
    When a client sends GET /candidates
    Then "gradle" has a default of "8.14"

  Scenario: stable wins over lts
    Given the candidate "gradle" is registered
      And version "8.14" of "gradle" on "UNIVERSAL" is tagged "stable"
      And version "9.8.1" of "gradle" on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "gradle" has a default of "8.14"

  Scenario: stable on LINUX_X64 wins over lts on UNIVERSAL
    Given the candidate "kuml" is registered
      And version "0.20.5" of "kuml" on "LINUX_X64" is tagged "stable"
      And version "0.21.0" of "kuml" on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "kuml" has a default of "0.20.5"

  Scenario: UNIVERSAL wins when stable exists at both platforms
    Given the candidate "scala" is registered
      And version "3.4.3" of "scala" on "UNIVERSAL" is tagged "stable"
      And version "3.3.1" of "scala" on "LINUX_X64" is tagged "stable"
    When a client sends GET /candidates
    Then "scala" has a default of "3.4.3"

  Scenario: A candidate without stable falls back to lts at UNIVERSAL
    Given the candidate "gradle" is registered
      And no "gradle" version is tagged "stable"
      And version "8.14" of "gradle" on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "gradle" has a default of "8.14"

  Scenario: lts falls back to LINUX_X64
    Given the candidate "kuml" is registered
      And no "kuml" version is tagged "stable"
      And version "0.20.5" of "kuml" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "kuml" has a default of "0.20.5"

  Scenario: stable on another platform is ignored
    Given the candidate "kuml" is registered
      And version "0.21.0" of "kuml" on "MAC_ARM64" is tagged "stable"
      And version "0.20.5" of "kuml" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "kuml" has a default of "0.20.5"

  Scenario: latest never produces a default
    Given the candidate "jpx" is registered
      And version "0.15.5" of "jpx" on "UNIVERSAL" is tagged "latest"
      And no "jpx" version is tagged "stable" or "lts"
    When a client sends GET /candidates
    Then "jpx" is listed
      And "jpx" has no default

  Scenario: Each candidate resolves its own tag
    Given the candidates "gradle" and "scala" are registered
      And version "8.14" of "gradle" on "UNIVERSAL" is tagged "stable"
      And version "3.4.3" of "scala" on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "gradle" has a default of "8.14"
      And "scala" has a default of "3.4.3"
```

The last scenario guards the per-candidate cut: a registry-wide choice of tag would resolve `scala`
against `stable` and leave it without a default.

## Rollout

Ships with no visible change: every live candidate keeps its current default until someone tags
`stable`. `sdkman-state` deploys on push. The Candidates Service needs no deploy; it reads the
derived value and picks up a moved default within its usual refresh window (State `max-age` plus the
in-process refresh, up to fifteen minutes).

## Workspace follow-ups

Outside `sdkman-state`, tracked here so they land with it:

- **`sdkman-candidates`, wording only.** Comments in `CandidateRegistry.scala`,
  `DefaultController.scala` and `CandidatesListController.scala`, and
  `specs/candidate-registry-read-flip.md`, describe the non-java default as "the `lts`-tagged
  version". Reword to "the default State derives". No code change, no deploy.
- **`docs/glossary.md` §default / lts tag.** "Default" stops meaning "the version carrying `lts`".
  Non-java: first of `stable`, then `lts`. Java: Temurin `lts` at `LINUX_X64`.
- **Local seed.** `seed/seed.sh` tags one non-java fixture `stable` on a version other than its
  `lts`. `gradle` fits: no Insomnia folder pins its default. Tag `8.5` `stable` and leave `8.14` on
  `lts`.
- **Insomnia folder `12 candidate registry (Local only)`.** Assert that `GET /default/gradle` and
  gradle's `sdk list` header both return `8.5`.

## Out of Scope

- **java.** Its default remains the Temurin `lts` at `LINUX_X64`, resolved by the Candidates
  Service. Rule 8 of [`candidate-registry.md`](candidate-registry.md) is unchanged.
- **Moving existing `lts` tags to `stable`.** No data migration. Owners opt in by publishing.
- **Validation against mixing.** A candidate may carry both tags; `stable` simply wins.
- **`vendor-release`.** It keeps writing `lts`.
- **`latest`, or any third tier.**

## Acceptance Criteria

- Every scenario in §Examples passes, alongside the unchanged java scenario of
  [`candidate-registry.md`](candidate-registry.md).
- `GET /candidates` still resolves every default in a single query, whatever the registry size.
- For a candidate with no `stable` tag, `GET /candidates` returns exactly the `default` it returns
  before this change.
- `GET /candidates` against production returns the same `default` for all 77 non-java candidates
  before and after deploy.
- The project's quality gates pass.
