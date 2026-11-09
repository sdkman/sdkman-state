# Java `latest` and `lts`

`lts` and `latest` are the two java tags a user reaches for without knowing a version: `lts` is
the stable choice and carries the java default, `latest` is the bleeding edge. Under
[`java-supersession.md`](java-supersession.md) each is moved one post at a time, by rules local to
one distribution and platform, and neither rule knows which java release is current. So both
drift. On 2026-11-09 `latest` sits on Zulu `17.0.21.0+9` on `LINUX_X32`, Corretto `11.0.33.0+9` on
`LINUX_X32`, SAP Machine `21.0.13.0+3` on `MAC_X64` and Temurin `17.0.20.1+1` on `MAC_X64`: each
was the newest build a post brought to a platform that never ships the distribution's newest
release. Meanwhile `lts` is missing from eleven of sixteen distributions, because a vendor post only
moves `lts` within the series that already holds it, and nothing ever put it there.

This change makes both tags a function of the data and one setting. State knows the current LTS
feature from configuration. After every java vendor write it places `lts` and `latest` across the
whole distribution, so a tag is always where the rules say, and a stray one heals on the next post.
An admin route applies the same placement on demand, for the rollout and after the LTS feature
moves on.

*Reference: replaces the `lts` and `latest` rules of [`java-supersession.md`](java-supersession.md)
(§Tags bullets two and three, rules 19 and 20, and its out-of-scope entry on choosing the LTS
series), and widens its write turn (rule 26) from distribution and platform to distribution. The
`<FEATURE>` tag, series, precedence, visibility and the admin override stand as written there.
Where the two conflict, this one wins.*

## Behaviour

### The LTS feature

State is configured with one number, the **LTS feature**: the FEATURE of the newest java LTS
release, `25` today, `29` from September 2027. It is the only input the rules take beyond the
stored rows. It decides where `lts` goes, and `latest` only ever sits above it.

```hocon
java {
    lts {
        feature = 25
        feature = ${?JAVA_LTS_FEATURE}
    }
}
```

It is read once at startup. A missing or non-numeric value fails the start.

### Placement

The rules work on a **distribution**: every java row sharing a distribution, across all its
platforms. An absent distribution counts as a value of its own. Only **plain heads** count, the
visible heads of plain series as [`java-supersession.md`](java-supersession.md) defines them;
`fx` and `crac` rows, hidden rows and unreadable rows never receive `lts` or `latest`.

**`lts` goes to the newest release of the LTS feature, on every platform that has it.** On each
platform, `lts` sits on the highest plain head with FEATURE equal to the LTS feature, interim
lines included. For most distributions that is the feature line. GraalVM ships stable interim
releases on top of its LTS feature, so its `lts` is on the interim line: `25.4.4.1+1`, not
`25.0.5.0+3`. The `<FEATURE>` tag already goes there under
[`java-supersession.md`](java-supersession.md) rule 18, so `25` and `lts` share that row.

**A platform without the LTS feature keeps what it has.** If a platform has no plain head of the
LTS feature, `lts` is not placed on it afresh, and an `lts` it already holds stays in
its series: it moves to that series' plain head when the series has one, and otherwise is left
where it is. This is the hand-over. When the LTS feature moves from 25 to 29, each platform keeps
its 25 `lts`, and the java default with it, until that platform has a 29 to take it. A platform
that never had `lts` never gains one on an older feature.

**`latest` goes to the distribution's newest feature, and only on platforms that have it.** The
distribution's **top feature** is the highest FEATURE among its plain heads on every platform,
early access included. On each platform that has a plain head of the top feature, `latest` sits on
the highest of them, interim lines included. A platform without the top feature has no `latest`:
it never falls back to an older feature, however long ago that platform's last release was.

**`latest` is always above `lts`.** If a distribution's top feature is not above the LTS feature,
the distribution has no `latest` at all. A distribution that ships only LTS releases (Microsoft,
JetBrains, Mandrel today) has a stable choice and no bleeding edge, so it carries `lts` and never
`latest`. GraalVM is one of them: its interim releases build on the LTS feature, so they take `lts`,
not `latest`. `lts` and `latest` therefore never share a row.

**Each tag is on exactly the rows the rules name, and nowhere else.** Placing a tag on a platform
takes it off every other row of that platform, hidden and unreadable rows included. A platform the
rules give no `latest` loses the one it holds.

**When the rules cannot single out a row, the tag stays put.** If the candidate heads for a tag on
a platform cannot be ordered against each other, `lts` or `latest` stays on whichever of them holds
it, and otherwise is not placed. Nothing else on that platform changes.

*(Assumption, drafting 2026-11-09: candidate heads that cannot be ordered arise only within one
series, from unknown build metadata at equal core, such as `25.0.5.0+r17` beside `25.0.5.0+1`.
Heads of different series always differ in core and so always order. None is unordered on live
today.)*

### When placement runs

**After every java vendor write.** A vendor's `POST /versions/java` that is stored, as the head or
hidden, and a vendor's `DELETE /versions/java` that deletes, place `lts` and `latest` across the
post's whole distribution, in the same transaction as the write. A refused write places nothing.
So a vendor post on one platform can move a tag on another: the first post of a new top feature
takes `latest` off every platform that does not have it yet.

**On demand, by the admin.** `POST /versions/java/retag` places `lts` and `latest` across every
java distribution. It is the same placement, run without a write. It is idempotent: a second call
changes nothing.

**Not after an admin write.** An admin `POST /versions/java` keeps its literal tags, and the admin's
`POST /versions/tags` and `DELETE /versions/tags` still move any java tag, `lts` and `latest`
included. Those placements hold until the distribution's next vendor write, or the next retag,
which put `lts` and `latest` back where the rules say. The LTS feature, not a tag call, is how the
admin chooses where `lts` goes.

### Writes

Every java write for one distribution takes its turn, whoever sends it: vendor and admin
`POST /versions/java` and `DELETE /versions/java`, the admin's java tag calls, and the retag for
that distribution. The turn was distribution and platform; placement spans platforms, so it is now
the distribution. A write's upsert, hides, `<FEATURE>` move and placement commit together or not at
all.

The retag takes each distribution's turn in sequence and commits each distribution on its own. A
failure leaves earlier distributions placed and later ones untouched, and the call answers `500`;
calling it again finishes the job.

*(Assumption, drafting 2026-11-09: the turn stays a transaction-scoped Postgres advisory lock, now
keyed on `java` and the distribution only.)*

Tag moves stay unaudited, as today. The `CREATE` entry of a post records the tags its row held when
the post committed.

## API Contract

### `POST /versions/java/retag`

Authenticated, admin only. No body.

| Status | Body | When |
|---|---|---|
| `204 No Content` | empty | Every java distribution is placed |
| `401 Unauthorized` | empty | Missing or invalid token |
| `403 Forbidden` | `ErrorResponse` | Any role but the admin |
| `500 Internal Server Error` | `ErrorResponse` | Database failure; distributions placed before it stay placed |

### Other routes

| Route | Change |
|---|---|
| `POST /versions/java` (vendor) | After a stored post, `lts` and `latest` are placed across the post's distribution. Statuses unchanged |
| `DELETE /versions/java` (vendor) | After a delete, `lts` and `latest` are placed across the row's distribution. Statuses unchanged |
| `POST /versions/java` (admin), `POST /versions/tags`, `DELETE /versions/tags` | Unchanged. Their `lts` and `latest` hold until the next vendor write or retag |
| Read routes | Unchanged |

## Business Rules

### Configuration

1. **One setting.** `java.lts.feature` is the FEATURE of the newest LTS release. It is required,
   read at startup, and overridable with `JAVA_LTS_FEATURE`.

### `lts`

2. **On the newest release of the LTS feature.** On each platform with a plain head of FEATURE
   equal to the LTS feature, `lts` is on the highest such head, interim lines included.
3. **Kept, never placed afresh, without it.** On a platform with no such head, an `lts` already
   held moves to its own series' plain head, or stays where it is if that series has none. A
   platform without `lts` gains none.

### `latest`

4. **The top feature** is the highest FEATURE among the distribution's plain heads on all
   platforms, early access included.
5. **On the top feature, where a platform has it.** On each platform with a plain head of the top
   feature, `latest` is on the highest such head, interim lines included.
6. **No fallback.** A platform without a plain head of the top feature has no `latest`.
7. **Above `lts`.** A distribution whose top feature is not above the LTS feature has no
   `latest`.

### Both

8. **Plain heads only.** `fx` and `crac` rows, hidden rows and unreadable rows never carry `lts` or
   `latest` after placement.
9. **Exactly where the rules say.** Placing a tag on a platform removes it from every other row of
   that platform; a platform the rules give no tag loses the one it holds, except under rule 3.
10. **Never on one row.** Rules 2, 3 and 7 keep `latest` on a higher feature than any `lts`.
11. **Undecidable means untouched.** When the candidate heads for a tag cannot be ordered, the tag
    stays on whichever of them holds it, or is not placed.
12. **`<FEATURE>` is unchanged.** It moves as [`java-supersession.md`](java-supersession.md) states.

### Running placement

13. **After every vendor write that changes State.** A stored vendor post or a vendor delete places
    both tags across its whole distribution, in the write's transaction.
14. **Refusals place nothing.** A `400`, `403`, `404` or `409` changes no tag.
15. **Retag on demand.** `POST /versions/java/retag` places both tags across every distribution.
    Admin only; idempotent.
16. **Admin writes are literal and temporary.** An admin post or tag call places `lts` and `latest`
    as sent, until the distribution's next vendor write or retag.
17. **One distribution at a time.** Every java write takes the distribution's turn, so a concurrent
    write never sees a half-placed distribution.

## Findings

Modelled on 2026-11-09 against every java row on `state.sdkman.io` (9,915 rows, 16 distributions),
with the LTS feature at 25. The first vendor write to each distribution, or the rollout's retag,
makes 89 changes. The java default does not move: Temurin `LINUX_X64` keeps `lts` on `25.0.5.0+8`.

- **Six stray `latest` go**, each on a platform without its distribution's top feature: Zulu
  `LINUX_X32` (`17.0.21.0+9`), Corretto `LINUX_X32` (`11.0.33.0+9`), Liberica `LINUX_X32`
  (`11.0.33.0+11`, below even that platform's own 21), SAP Machine `MAC_X64` (`21.0.13.0+3`),
  Temurin `MAC_X64` (`17.0.20.1+1`) and OpenJDK `MAC_X64` (`25.0.2.0+10`, where the top feature is
  28 early access). This is the [#172](https://github.com/sdkman/sdkman-state/issues/172) behaviour,
  now prevented rather than cleaned up by hand.
- **51 `lts` arrive** on 25. Eleven distributions never had one: Microsoft, Oracle, Semeru,
  OpenJDK, Kona, Liberica NIK, GraalVM and GraalCE gain it on all five main platforms, SAP Machine
  and Mandrel on four and Bisheng on two. Liberica gains it on `LINUX_ARM32HF`. GraalVM's lands on
  `25.4.4.1+1` and GraalCE's on `25.5.5.0+3`, beside their `25` tag. The admin tag calls queued for
  Microsoft today become unnecessary.
- **32 more `latest` go** from the seven distributions that ship nothing above 25: GraalCE,
  GraalVM, JetBrains, Kona, Liberica NIK, Mandrel and Microsoft. Bisheng, the eighth, has none
  today. Each keeps `lts` and its `25` tag on the same row.
- **An LTS release opens a gap in `latest`.** While the LTS feature is also the newest GA feature,
  a distribution has `latest` only if it publishes early access of the next one, as OpenJDK does.
  Under these rules Temurin would have had no `latest` from 25's GA in September 2025 until 26's in
  March 2026. That is intended: in that window there is nothing beyond the stable choice.
- **Stale visible rows still attract tags.** GraalVM and GraalCE still show `25.0.1` on `MAC_X64`,
  which the vendor stopped building, so it takes `lts` there. Semeru shows
  `25.0.1.0` on `MAC_ARM64` against `25.0.4.1+1` on Linux. These are visibility gaps from DISCO's
  push-once behaviour, not tag faults, and stay out of scope.
- **The admin tag scripts stop being routine.** Since 2026-10-22 the admin has run a tag script
  for Temurin, Zulu, Corretto and Liberica, and one is queued for Microsoft, Temurin, Zulu and
  OpenJDK, mostly to place `lts` or remove a stray `latest`. After this ships, a misplaced `lts` or
  `latest` is a bug in State, not a chore.

## Rollout

**One release, then one retag.** Deploy with `java.lts.feature = 25`, then call
`POST /versions/java/retag` once as the admin and check the tags through the read routes. Until the
retag, each distribution converges on its next vendor write.

**DISCO changes nothing.** It already sends no tags.

**Moving the LTS feature.** In September 2027, once Temurin 29 is on `LINUX_X64` and the default
should move, set `JAVA_LTS_FEATURE=29`, redeploy and retag. `lts`, and the java default with it,
moves to 29 on every platform that has it; platforms without 29 keep their 25 `lts` until they do
(rule 3). Distributions whose top feature is not above 29 lose `latest` until they publish 30
(rule 7).

**Rollback.** Redeploy the previous release. Tags stay where this release put them, and the old
rules move them one post at a time from there. No data needs undoing.

## Consumers

- **`sdkman-candidates`** reads `lts` scoped to Temurin and `LINUX_X64` for the java default.
  Unchanged; the rollout does not move it.
- **DISCO** posts without tags and handles no new status. Unchanged.

## Examples

*(Assumption, drafting 2026-11-09: "tagged X" asserts the row holds at least that tag; "untagged"
asserts it holds none of `lts` and `latest`.)*

```gherkin
Feature: Java latest and lts placement

  Background:
    Given DISCO is a vendor with "java" in scope
      And the LTS feature is 25

  # --- latest ---

  Scenario: A platform without the top feature loses latest
    Given ZULU 27.0.1.0+9 is visible on LINUX_X64 and tagged "latest"
      And ZULU 17.0.20.1+1 is visible on LINUX_X32 and tagged "latest"
    When DISCO posts ZULU 17.0.21.0+9 for LINUX_X32
    Then ZULU 17.0.21.0+9 is visible on LINUX_X32 and not tagged "latest"
      And no ZULU row on LINUX_X32 is tagged "latest"
      And ZULU 27.0.1.0+9 is still tagged "latest" on LINUX_X64

  Scenario: The first post of a new top feature takes latest off the other platforms
    Given TEMURIN 27.0.1.0+9 is visible and tagged "latest" on LINUX_X64 and MAC_ARM64
    When DISCO posts TEMURIN 28.0.0.0+ea.5 for LINUX_X64
    Then TEMURIN 28.0.0.0+ea.5 is tagged "latest" on LINUX_X64
      And no TEMURIN row on MAC_ARM64 is tagged "latest"
    When DISCO posts TEMURIN 28.0.0.0+ea.5 for MAC_ARM64
    Then TEMURIN 28.0.0.0+ea.5 is tagged "latest" on MAC_ARM64

  Scenario: Early access is the bleeding edge
    Given OPENJDK 27.0.1.0+3 and 28.0.0.0+ea.23 are visible on LINUX_X64
    When DISCO posts OPENJDK 28.0.0.0+ea.24 for LINUX_X64
    Then OPENJDK 28.0.0.0+ea.24 is tagged "latest"

  Scenario: A distribution below the LTS feature has no latest
    Given KONA 21.0.9.0+1 is the highest KONA row on every platform, visible and tagged "latest"
    When DISCO posts KONA 21.0.10.0+1 for LINUX_X64
    Then no KONA row on any platform is tagged "latest"

  Scenario: A distribution whose newest feature is the LTS feature has no latest
    Given MICROSOFT 25.0.4.0+5 is visible on LINUX_X64 and tagged "latest" and "lts"
    When DISCO posts MICROSOFT 25.0.5.0+2 for LINUX_X64
    Then MICROSOFT 25.0.5.0+2 is tagged "lts"
      And no MICROSOFT row on LINUX_X64 is tagged "latest"

  # --- lts ---

  Scenario: lts arrives on a distribution that never had it
    Given MICROSOFT 25.0.5.0+2 is visible on LINUX_X64 and not tagged "lts"
    When DISCO posts MICROSOFT 21.0.13.0+1 for LINUX_X64
    Then MICROSOFT 25.0.5.0+2 is tagged "lts"
      And MICROSOFT 21.0.13.0+1 is not tagged "lts"

  Scenario: lts follows the newest interim release of the LTS feature
    Given GRAALVM 25.0.5.0+3 and 25.4.4.1+1 are visible on LINUX_X64
      And GRAALVM 25.4.4.1+1 is tagged "25" and "latest"
    When DISCO posts GRAALVM 25.5.0.0+1 for LINUX_X64
    Then GRAALVM 25.5.0.0+1 is tagged "25" and "lts"
      And GRAALVM 25.4.4.1+1 is hidden and untagged
      And GRAALVM 25.0.5.0+3 is visible and untagged
      And no GRAALVM row on LINUX_X64 is tagged "latest"

  Scenario: lts never lands afresh on an older feature
    Given LIBERICA 21.0.13.0+11 is the highest LIBERICA row on LINUX_X32 and untagged
    When DISCO posts LIBERICA 21.0.13.0+12 for LINUX_X32
    Then no LIBERICA row on LINUX_X32 is tagged "lts"

  Scenario: A platform keeps its lts until it has the new LTS feature
    Given the LTS feature is 29
      And TEMURIN 29.0.0.0+35 is visible on LINUX_X64
      And TEMURIN 25.0.8.0+7 is visible and tagged "lts" on LINUX_X64 and MAC_X64
      And no TEMURIN 29 row exists on MAC_X64
    When the admin retags
    Then TEMURIN 29.0.0.0+35 is tagged "lts" on LINUX_X64
      And TEMURIN 25.0.8.0+7 is still tagged "lts" on MAC_X64

  Scenario: A kept lts follows its series
    Given the LTS feature is 29
      And TEMURIN 25.0.8.0+7 is visible and tagged "lts" on MAC_X64
      And no TEMURIN 29 row exists on MAC_X64
    When DISCO posts TEMURIN 25.0.9.0+5 for MAC_X64
    Then TEMURIN 25.0.9.0+5 is tagged "lts"
      And TEMURIN 25.0.8.0+7 is hidden and untagged

  # --- heals ---

  Scenario: A hidden post still heals the distribution
    Given TEMURIN 17.0.20.1+1 is visible on MAC_X64 and tagged "latest"
      And TEMURIN 26.0.2.1+1 is the highest TEMURIN row on MAC_X64
      And TEMURIN 27.0.1.0+9 is visible on LINUX_X64
    When DISCO posts TEMURIN 17.0.20.0+8 for MAC_X64
    Then TEMURIN 17.0.20.0+8 is hidden
      And no TEMURIN row on MAC_X64 is tagged "latest"

  Scenario: A tag on a hidden row moves to the head
    Given ZULU 25.0.4.1+1 is hidden on LINUX_X64 and tagged "lts"
      And ZULU 25.0.5.0+8 is visible on LINUX_X64
    When DISCO posts ZULU 21.0.13.0+9 for LINUX_X64
    Then ZULU 25.0.5.0+8 is tagged "lts"
      And ZULU 25.0.4.1+1 is untagged

  Scenario: A variant never takes either tag
    Given ZULU 27.0.1.0+9 is visible on MAC_X64
    When DISCO posts ZULU 27.0.2.0-crac+1 for MAC_X64
    Then ZULU 27.0.2.0-crac+1 is untagged
      And ZULU 27.0.1.0+9 is tagged "latest"

  Scenario: Heads that cannot be ordered keep the tag where it is
    Given KONA 25.0.5.0+r17 and 25.0.5.0+1 are both visible on LINUX_X64, as the admin left them
      And KONA 25.0.5.0+r17 is tagged "lts"
    When DISCO posts KONA 21.0.10.0+1 for LINUX_X64
    Then KONA 25.0.5.0+r17 is still tagged "lts"
      And KONA 25.0.5.0+1 is untagged

  Scenario: A refused post places nothing
    Given ZULU 17.0.21.0+9 is visible on LINUX_X32 and tagged "latest"
    When DISCO posts ZULU 25.0.5 for LINUX_X64
    Then the response status is 400
      And ZULU 17.0.21.0+9 is still tagged "latest"

  # --- admin ---

  Scenario: An admin tag call holds until the next vendor write
    Given TEMURIN 25.0.5.0+8 is visible on LINUX_X64 and tagged "lts"
      And TEMURIN 21.0.13.0+9 is visible on LINUX_X64
    When the admin assigns the tag "lts" to TEMURIN 21.0.13.0+9 on LINUX_X64
    Then TEMURIN 21.0.13.0+9 is tagged "lts"
    When DISCO posts TEMURIN 17.0.21.0+9 for LINUX_X64
    Then TEMURIN 25.0.5.0+8 is tagged "lts"
      And TEMURIN 21.0.13.0+9 is not tagged "lts"

  Scenario: The admin retags every distribution
    Given ZULU 17.0.21.0+9 is visible on LINUX_X32 and tagged "latest"
      And MICROSOFT 25.0.5.0+2 is visible on LINUX_X64 and not tagged "lts"
    When the admin sends POST /versions/java/retag
    Then the response status is 204
      And no ZULU row on LINUX_X32 is tagged "latest"
      And MICROSOFT 25.0.5.0+2 is tagged "lts"

  Scenario: Retag is idempotent
    Given the admin has retagged
    When the admin sends POST /versions/java/retag
    Then the response status is 204
      And no tag has moved

  Scenario: Only the admin may retag
    When DISCO sends POST /versions/java/retag
    Then the response status is 403
```

## Out of Scope

- **The `<FEATURE>` tag.** It keeps the rules of [`java-supersession.md`](java-supersession.md).
- **Tags on `fx` and `crac` series.**
- **Visibility.** Which rows are visible, and the stale rows DISCO never retires, are
  [`java-supersession.md`](java-supersession.md)'s concern.
- **Deriving the LTS feature from the calendar.** The release train makes it predictable, but the
  LTS cadence has changed before, and moving the java default is a decision.
- **A `stable` tag, or any other tag, for java.**
- **The java default's scope** in `sdkman-candidates`.
- **Any candidate other than java.**

## Acceptance Criteria

- [ ] Every scenario in §Examples passes as an acceptance test
- [ ] `java.lts.feature` is declared in `application.conf` with a `JAVA_LTS_FEATURE` override, read
      as a required value, and a missing or non-numeric value fails startup
- [ ] After every stored vendor `POST /versions/java` and every vendor `DELETE /versions/java`, the
      write's distribution satisfies rules 2 to 11 on every platform
- [ ] A refused java write moves no tag
- [ ] `POST /versions/java/retag` answers each status in its table, places every distribution, and
      changes nothing on a second call
- [ ] An admin `POST /versions/java` and the admin's java tag calls behave as before
- [ ] Concurrent java writes to one distribution, on any platforms, leave it satisfying rules 2 to 11
- [ ] A failure in a write's placement leaves nothing of the write committed
- [ ] Deployment check: after the rollout retag on live, Temurin `LINUX_X64` `lts` has not moved,
      and every distribution satisfies rules 2 to 11
- [ ] `src/main/resources/openapi/documentation.yaml` documents `POST /versions/java/retag`, and the
      placement that follows a vendor post or delete on the java routes
- [ ] All quality gates pass (`./gradlew check`: build, detekt, ktlint, tests)
