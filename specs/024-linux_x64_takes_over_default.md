---
id: "024"
title: "LINUX_X64 Takes Over the Default"
status: implemented
created: 2026-11-21
depends_on: ["022"]
---

# LINUX_X64 Takes Over the Default

[`022-non_java_version_rules.md`](022-non_java_version_rules.md) keeps a candidate's `stable`
from disagreeing with itself across platform shapes: tagging any platform row `stable` clears
`stable` from another version's `UNIVERSAL` row. The default cascade reads only `stable` at
`UNIVERSAL`, then `stable` at `LINUX_X64`. So when a candidate moves from a `UNIVERSAL` build to
platform builds, and a platform other than `LINUX_X64` is tagged first, the `UNIVERSAL` default
is cleared before anything can take its place. The candidate has no `default` until `LINUX_X64`
is tagged, and for good if the release has no `LINUX_X64` build. The Candidates Service can
cache that empty answer for up to fifteen minutes.

This feature narrows the clear: only a `LINUX_X64` `stable`, the one that can take over the
default, clears another version's `UNIVERSAL` `stable`. The default then moves in one step,
from the old `UNIVERSAL` version to the new `LINUX_X64` one.

*Reference: raised in review of sdkman-state PR #193 on 2026-11-21. Builds on
[`022-non_java_version_rules.md`](022-non_java_version_rules.md). Where earlier specs conflict,
this one wins.*

## Behaviour

**Of the platform rows, only `LINUX_X64` clears a `UNIVERSAL` `stable`.** Tagging a non-java
`LINUX_X64` row `stable` also clears `stable` from any other version's `UNIVERSAL` row. Tagging
a row `stable` on a platform other than `LINUX_X64` or `UNIVERSAL` leaves every `UNIVERSAL` row
as it was.

**A `UNIVERSAL` `stable` still clears every platform.** Tagging a `UNIVERSAL` row `stable`
clears `stable` from every other version's platform rows, as 022 says.

**Setting a tag never costs a candidate its default.** Removing `stable` with
`DELETE /versions/tags` still can, as today. While a candidate moves from a
`UNIVERSAL` build to platform builds, the default stays on the `UNIVERSAL` version until
`LINUX_X64` is tagged, then moves to it. Until then a platform's `stable` lookup can answer a
newer version than the default. A release with no `LINUX_X64` build leaves the default on the
`UNIVERSAL` version.

This replaces 022's guarantee that the version the default cascade picks is always the version
every platform's `stable` lookup answers.

**Both ways of tagging agree.** The `tags` field of `POST /versions` and the tag route
`POST /versions/tags` apply the narrowed clear identically. The clear never refuses a write, so
`POST /validate/versions` answers as before.

## Rollout

No data fix. Under 022, a `stable` on a platform other than `LINUX_X64` cleared another
version's `UNIVERSAL` `stable`, which can leave a candidate with no default. This release stops
that for future writes. It does not repair a candidate already left without one.

**Rollback.** Redeploy the previous release. Writes clear across shapes as 022 says again;
nothing already written changes.

## Examples

```gherkin
Feature: LINUX_X64 takes over the default

  Background:
    Given "jpx 1.0.0" for UNIVERSAL is tagged "stable"

  Scenario: Another platform first keeps the old default
    When "jpx 2.0.0" for MAC_ARM64 is posted with tags ["stable"]
    Then "jpx 1.0.0" for UNIVERSAL carries "stable"
      And "jpx"'s default is 1.0.0
      And the MAC_ARM64 "stable" of "jpx" is 2.0.0

  Scenario: LINUX_X64 after another platform moves the default
    Given "jpx 2.0.0" for MAC_ARM64 is tagged "stable"
    When "jpx 2.0.0" for LINUX_X64 is posted with tags ["stable"]
    Then "jpx 1.0.0" carries no "stable" tag
      And "jpx"'s default is 2.0.0

  Scenario: LINUX_X64 first moves the default
    When "jpx 2.0.0" for LINUX_X64 is posted with tags ["stable"]
    Then "jpx 1.0.0" carries no "stable" tag
      And "jpx"'s default is 2.0.0

  Scenario: A release with no LINUX_X64 build keeps the old default
    When "jpx 2.0.0" for MAC_ARM64 and WINDOWS_X64 is posted with tags ["stable"]
    Then "jpx"'s default is 1.0.0

  Scenario: The tag route keeps the old default
    Given "jpx 2.0.0" exists for MAC_ARM64 with no tags
    When "jpx 2.0.0" for MAC_ARM64 is tagged "stable" through POST /versions/tags
    Then the response status is 204
      And the MAC_ARM64 "stable" of "jpx" is 2.0.0
      And "jpx 1.0.0" for UNIVERSAL carries "stable"
```

## Out of Scope

- java versions and tags.
- Rows already in State.
- How the default cascade reads `stable`: still `UNIVERSAL`, then `LINUX_X64`.

## Acceptance Criteria

- [ ] Tagging a non-java `LINUX_X64` row `stable` clears `stable` from other versions' `UNIVERSAL` rows
- [ ] Tagging a non-java row `stable` on a platform other than `LINUX_X64` or `UNIVERSAL` leaves every `UNIVERSAL` row unchanged
- [ ] Tagging a `UNIVERSAL` row `stable` still clears it from other versions' platform rows
- [ ] Both publish orders (another platform then `LINUX_X64`, and the reverse) and a release with no `LINUX_X64` build are covered by acceptance tests, and no order leaves a candidate without a default
- [ ] The `tags` field and the tag route apply the clear identically
- [ ] java writes behave as before
- [ ] OpenAPI's `POST /versions/tags` description and the `tags` property say that, of the platform rows, only a `LINUX_X64` `stable` clears other versions' `UNIVERSAL` `stable`
- [ ] All quality gates pass (`./gradlew check`)
