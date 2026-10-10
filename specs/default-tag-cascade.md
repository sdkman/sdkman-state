# Default Tag Cascade

`GET /candidates` gives each non-java candidate a derived `default`, today the version carrying the
`lts` tag. For java, `lts` is the right word, since JEP 322 defines it. Most other tools have no
long-term-support line, and the tag their maintainers would naturally use is `stable`.

This feature lets a non-java candidate's `default` come from a `stable` tag, falling back to `lts`
when there is none. Nothing else about the derived default changes.

*Amends [`candidate-registry.md`](candidate-registry.md): rules 4, 5 and 7, the `default` row of its
API contract, the `lts` sentences of its §Behaviour, its out-of-scope entry on per-candidate defaults,
its `default` acceptance criteria, and four of its default scenarios. Rules 6 and 8 stand: only rows
with no distribution count, and java never carries a `default`.*

## Behaviour

A non-java candidate's `default` is the first of these that exists:

1. the version tagged `stable` at `UNIVERSAL`
2. the version tagged `stable` at `LINUX_X64`
3. the version tagged `lts` at `UNIVERSAL`
4. the version tagged `lts` at `LINUX_X64`

The **tag decides first, then the platform**. `stable` on either platform beats `lts` on any
platform. A candidate matching none of the four has no `default`, and the field is absent, as today.

A candidate's `lts` tag stops producing its `default` only once a `stable` tag exists at
`UNIVERSAL` or `LINUX_X64` on a row with no distribution. A `stable` anywhere else is inert and
`lts` still resolves.

## Business Rules

These replace rules 4, 5 and 7 of [`candidate-registry.md`](candidate-registry.md).

4. **`default` is derived, never stored.** It is read from `version_tags` on every request. Nothing
   writes a default onto a candidate row and there is no endpoint to set one. Moving a default means
   moving the `stable` tag, or the `lts` tag for a candidate with no `stable`.
5. **`default` cascades by tag, then by platform:** `stable` before `lts`, and within each tag
   `UNIVERSAL` before `LINUX_X64`. The four combinations are the only ones that count; nothing
   outside them can produce a default, however it would sort. `latest`, a major-version tag such as
   `9`, or a `stable` or `lts` tag that exists only on `MAC_ARM64` never produces one. Tag names
   match exactly; `Stable` is a different tag and never produces a default.
7. **`default` does not filter on `visible`, and agrees with the tag route.** The derived value
   equals what `GET /versions/{candidate}/tags/{tag}?platform={platform}` returns for the tag and
   platform that won the cascade, including when that version is no longer visible. Tag and
   platform are read from `version_tags`, the same columns the tag route reads; distribution is
   read from `versions`, per rule 6.

## Findings

- **Shipping this changes no live default.** A survey of `state.sdkman.io` on 2026-10-10 found every
  non-java candidate tagged `lts`, five also tagged `latest`, and none tagged `stable`.
- **`latest` is deliberately excluded.** It means newest, not chosen, and on live it already
  disagrees with the default: `kotlintoolchain` carries `latest` on 0.12.1 and `lts` on 0.13.0.
- **The resolution traps in [`candidate-registry.md`](candidate-registry.md) §Findings still apply,
  now across two tags.** Resolution stays one query for the whole registry. The choice of tag is made
  per candidate, never once for the whole registry. The tag and platform predicates must exclude
  everything outside the four combinations, not merely sort them: sorting alone would let `latest`
  or a `MAC_ARM64`-only tag through.
- **`stable` needs no write-side change.** `TagNameRules` already accepts it as a tag name.

## Examples

These replace the `gradle`, `kuml`, `scala` and `jpx` default scenarios in
[`candidate-registry.md`](candidate-registry.md) §Examples. The java scenario stays. The `connor`
scenario stays too, with `And no "connor" version is tagged "stable"` added.

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

  Scenario: UNIVERSAL wins when lts exists at both platforms
    Given the candidate "scala" is registered
      And no "scala" version is tagged "stable"
      And version "3.4.3" of "scala" on "UNIVERSAL" is tagged "lts"
      And version "3.3.1" of "scala" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "scala" has a default of "3.4.3"

  Scenario: stable on another platform is ignored
    Given the candidate "kuml" is registered
      And version "0.21.0" of "kuml" on "MAC_ARM64" is tagged "stable"
      And version "0.20.5" of "kuml" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "kuml" has a default of "0.20.5"

  Scenario: Tag names match exactly
    Given the candidate "kuml" is registered
      And version "0.21.0" of "kuml" on "UNIVERSAL" is tagged "Stable"
      And version "0.20.5" of "kuml" on "UNIVERSAL" is tagged "lts"
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

  Scenario: A stable default agrees with the tag route, even when retired
    Given the candidate "gradle" is registered
      And version "8.14" of "gradle" on "UNIVERSAL" is not visible and is tagged "stable"
    When a client sends GET /candidates
      And a client sends GET /versions/gradle/tags/stable?platform=UNIVERSAL
    Then "gradle" has a default of "8.14"
      And the tag route resolves "8.14"
```

The "each candidate" scenario guards the per-candidate choice of tag. If the tag were chosen once
for the whole registry, `scala` would be resolved against `stable` and end up with no default.

## Out of Scope

- **java.** Rule 8 of [`candidate-registry.md`](candidate-registry.md) is unchanged.
- **Moving existing `lts` tags to `stable`.** No data migration. Candidates opt in by tagging.
- **Validation against carrying both tags.** `stable` simply wins.
- **`latest`, or any further tier.**
- **Configuring the cascade per candidate.** The order is fixed for every non-java candidate.
- **Any change to `POST /versions`, the tag routes, or the version read routes.**

## Acceptance Criteria

- [ ] Every scenario in §Examples passes, alongside the java and `connor` scenarios of
      [`candidate-registry.md`](candidate-registry.md)
- [ ] `GET /candidates` still resolves every default without a query per candidate
- [ ] The existing `lts` default tests in `GetCandidatesAcceptanceSpec` and
      `CandidateDefaultAgreementAcceptanceSpec` still pass, changed at most by an added "no `stable`
      tag" given
- [ ] `src/main/resources/openapi/documentation.yaml` describes `default` as the cascade: `stable`
      before `lts`, `UNIVERSAL` before `LINUX_X64`, limited to those four combinations, rows with no
      distribution only, always absent for java. Both the `GET /candidates` route description and
      the `default` property say so.
- [ ] [`candidate-registry.md`](candidate-registry.md) points to this spec from each passage it
      amends
- [ ] The project's quality gates pass
