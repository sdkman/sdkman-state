---
id: "024"
title: "CLI Candidate Tags"
status: draft
created: 2026-11-20
depends_on: ["022"]
---

# CLI Candidate Tags

Phase 3 makes the two SDKMAN CLIs State candidates: `sdkcli` (bash, one `UNIVERSAL` row per
release) and `sdkrs` (native, one row per release per native platform). Each has two release
channels, `stable` and `beta`, and every installed CLI self-updates to whatever those tags
point at. Broker 2 answers the tagged version to old CLIs as plain text, and hooks reads the
same tags to build the selfupdate script.

[`022-non_java_version_rules.md`](022-non_java_version_rules.md) allows a non-java row one tag,
`stable` or `latest`, and swaps one for the other. Under it, `beta` is refused, and promoting a
beta to `stable` would leave the CLIs with no `beta` at all. This feature gives `sdkcli` and
`sdkrs` their own tag rule: `stable` and `beta`, independent of each other. Every other
non-java rule still applies to them.

*Reference: phase 3 of the MongoDB to PostgreSQL move, end-game §4.3
([`application-end-game.md`](../../../docs/specs/application-end-game.md)), and the Broker 2
version route ([`cli_version_by_tag.md`](../../../do/sdkman-broker/specs/cli_version_by_tag.md)).
Grilled on 2026-11-20. Where [`022-non_java_version_rules.md`](022-non_java_version_rules.md)
conflicts with this spec for `sdkcli` or `sdkrs`, this one wins. Builds on that spec, so it
ships with it or after it, and before the CLIs are registered (plan step 5).*

## Behaviour

**Two named candidates.** The rule applies to `sdkcli` and `sdkrs` and to no other candidate.
Like java, they are fixed in State's code, not configured.

**Two tags, `stable` and `beta`.** A CLI row may carry `stable`, `beta`, both, or neither. Any
other tag, `latest` and `lts` included, is refused with `400`.

**The tags are independent.** Setting one never removes the other from a row. Promoting a
beta means tagging its row `stable`, and it keeps `beta` until a newer beta takes it.

**Each tag still points at one row per platform.** As for every candidate today, tagging a row
moves that tag off whichever version held it on that platform.

**Everything else is unchanged.** The non-java version grammar and the one-platform-shape rule
bind CLI writes as they bind any other non-java candidate. Semver versions such as `5.24.0` and
`0.8.0` pass the grammar.

**Both ways of tagging obey the same rule.** The `tags` field of `POST /versions`, the tag
route `POST /versions/tags`, and the validation twin `POST /validate/versions` accept and
refuse CLI tags identically.

## API Contract

No new routes and no new request fields. For `sdkcli` and `sdkrs`:

| Route | Accepted | Refused with `400` |
|---|---|---|
| `POST /versions`, `POST /validate/versions` | `tags` of `[]`, `["stable"]`, `["beta"]`, `["stable", "beta"]` | any other tag |
| `POST /versions/tags` | `stable`, `beta` | any other tag |

Who may write is unchanged: admin, and a vendor whose login lists the candidate.

## Business Rules

1. **CLI tags.** A `sdkcli` or `sdkrs` row carries only `stable` and `beta`, in any
   combination. Any other tag is refused with `400`.
2. **Independent.** Setting `stable` or `beta` on a CLI row leaves the other tag on that row
   as it was.
3. **Moves per platform.** Tagging a CLI row moves that tag off any other version's row on the
   same platform.
4. **Other non-java rules hold.** The version grammar and the one-platform-shape rule apply to
   CLI writes unchanged.
5. **One answer.** The `tags` field, the tag route and the validation twin apply rules 1 to 3
   identically.

## Design Decisions

*Resolved in a grilling session on 2026-11-20.*

- **Fixed in code, like java.** A per-candidate tag setting in the registry would mean new
  schema and admin API for two candidates. `beta` for every non-java candidate would widen
  the vocabulary `sdkman-contrib` checks against.
- **Release credentials are a vendor login.** The release workflows log in as a vendor that
  owns only `sdkcli` and `sdkrs`, not as admin, so a leaked CI secret cannot touch any other
  candidate. Creating that login is an operations step; State needs no change for it.

## Examples

```gherkin
Feature: CLI candidate tags

  Scenario: A beta is accepted for a CLI candidate
    When the release vendor posts "sdkcli 5.24.0" for UNIVERSAL with tags ["beta"]
    Then the response status is 204

  Scenario: Promoting a beta keeps its beta tag
    Given "sdkcli 5.24.0" for UNIVERSAL is tagged "beta"
    When it is tagged "stable"
    Then it carries "stable" and "beta"

  Scenario: A new beta leaves stable alone
    Given "sdkcli 5.24.0" for UNIVERSAL is tagged "stable" and "beta"
    When "sdkcli 5.25.0" for UNIVERSAL is posted with tags ["beta"]
    Then "sdkcli 5.25.0" carries "beta"
      And "sdkcli 5.24.0" carries "stable" only

  Scenario: Native tags move per platform
    Given "sdkrs 0.8.0" for LINUX_X64 is tagged "beta"
    When "sdkrs 0.9.0" for LINUX_X64 is posted with tags ["beta"]
    Then "sdkrs 0.8.0" for LINUX_X64 carries no "beta" tag

  Scenario: latest is refused for a CLI candidate
    When the release vendor posts "sdkcli 5.25.0" for UNIVERSAL with tags ["latest"]
    Then the response status is 400

  Scenario: beta is still refused for other candidates
    When any caller posts "jpx 1.2.0" for UNIVERSAL with tags ["beta"]
    Then the response status is 400

  Scenario: The twin agrees with the write
    When "sdkcli 5.25.0" for UNIVERSAL with tags ["stable", "beta"] is sent to POST /validate/versions
    Then the response status is 204
```

## Out of Scope

- Registering `sdkcli` and `sdkrs`, and publishing their current releases (plan step 5).
- Creating the release vendor login.
- Keeping the CLIs out of `sdk list`: the clients filter them (end-game §4.1).
- java tags, and every other non-java candidate's tags.

## Acceptance Criteria

- [ ] `sdkcli` and `sdkrs` rows accept `stable` and `beta`, alone or together, through the `tags` field, the tag route and the validation twin
- [ ] Any other tag on a `sdkcli` or `sdkrs` row, `latest` and `lts` included, is refused with `400`
- [ ] Setting `stable` on a CLI row leaves its `beta`, and the reverse
- [ ] Tagging a CLI row moves that tag off other versions on the same platform
- [ ] The non-java version grammar and one-platform-shape rule still refuse bad CLI writes
- [ ] Other non-java candidates still refuse `beta`
- [ ] java writes behave as before
- [ ] OpenAPI documents the CLI tag rule
- [ ] All quality gates pass (`./gradlew check`)
