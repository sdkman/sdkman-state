# Java Supersession

The java listing should advertise one build per release series. Since
[`java-version-supersession.md`](java-version-supersession.md), a java `POST /versions` hides the
rest of its series whatever the order: the posted version wins. That assumed DISCO publishes
forward, one build at a time. It does not. On 2026-10-19 OpenJDK on `LINUX_X64` advertises
`26.0.0.0+35` while the newer `26.0.2.0+10` is hidden. Tags drift the same way: DISCO moves `lts`,
`latest` and the major tags through separate tag calls, and nothing keeps them on the rows the
listing advertises.

This change lets State decide. Java gets its own route, `POST /versions/java`. A vendor post is
ordered against its series: a newer build becomes the one visible row and takes the series' tags,
an older one is stored hidden. The admin keeps a literal override, including for legacy rows the
grammar rejects. The semverish feature flag goes: semverish is simply the java route's grammar.

*Reference: replaces the positional rule of [`java-version-supersession.md`](java-version-supersession.md)
and its eligibility grammar as amended by [`semverish-four-component-core.md`](semverish-four-component-core.md).
Keeps the grammar of [`semverish-open-ended-core.md`](semverish-open-ended-core.md) and removes its
feature flag. Amends [`community-role.md`](community-role.md) for java only. Tag moves use the unique
tag scope of [`assign-tag-to-version.md`](assign-tag-to-version.md). Settled in a grilling session.
Where the earlier specs conflict with this one, this one wins.*

## Behaviour

`POST /versions/java` is the only route that writes a java version, and `DELETE /versions/java`
the only one that deletes one. On the post, the caller decides what it does:

- **A vendor with `java` in scope** (DISCO today) publishes. State orders the post against its
  series and decides its visibility and tags. The vendor sends neither.
- **The admin** overrides. Visibility and tags are stored as posted, and no ordering applies.
- **Anyone else** is refused with `403`, the community role included.

`POST /versions` and `DELETE /versions` reject `java` with `400`, pointing at the java routes.
`POST /versions/tags` and `DELETE /versions/tags` refuse java to everyone but the admin. The read
routes are unchanged. No rule in this spec applies to a candidate other than java.

`POST /versions/java` requires `java` in the registry, as `POST /versions` requires its
candidate. If `java` is unregistered it answers `400` with a `request` error naming it.

### Grammar

A vendor's version must match the grammar of [`semverish-open-ended-core.md`](semverish-open-ended-core.md), unchanged:

```
<feature>.<interim>.<update>.<patch>[.<trailing>...][-<variant>][+<build-metadata>]
```

That spec's feature flag is removed. `validation.semverish.enabled` leaves `application.conf` and
`SEMVERISH_VALIDATION_ENABLED` is no longer read. Semverish is what the java route accepts; the
generic route applies it to nothing.

The admin may also post a version that fails the grammar, a **legacy** version. It is stored as
given and takes part in no series when posted. This is the API route for editing or unhiding the
legacy rows the migration carried over.
Once stored, a spelling that reads into a series (`17.0.20`, `26.0.2.fx`) is hidden by the next
vendor post that becomes that series' head; an unreadable one (`25.0.4.r25`) stays as the admin
left it.

### Reading stored rows

Stored rows carry both generations of spelling. For comparison only, a stored version is read
through two normalisations. The stored string is never rewritten.

- A three-component core is padded to four: `27.0.0+35` reads as `27.0.0.0+35`, `17.0.20` as `17.0.20.0`.
- A legacy dot variant reads as a semverish one: `26.0.2.fx` reads as `26.0.2.0-fx`, `17.0.19.crac` as `17.0.19.0-crac`.

A stored row is **readable** when the result matches the grammar. Anything else, such as
`25.0.4.r25`, `25.r25` or `16.ea.36`, is **unreadable**: it belongs to no series, is never hidden
by a post and never blocks one.

### Series

A **series** is the set of readable java rows sharing distribution, platform, feature, release
line and variant. The feature is FEATURE, the first core component. The release line follows
JEP 322's INTERIM, the second: the **feature line** when INTERIM is `0`, the **interim line**
otherwise. Each new interim release replaces the previous one, so `25.0.4.0` and `25.3.4.0` are
two series, while `25.3.4.0` and `25.4.1.0` are one. The variant is `fx`, `crac` or none. An absent distribution
counts as a value of its own.

The **plain series** (no variant) carries tags. `fx` and `crac` series supersede for visibility
only and never gain a tag from a vendor post.

The **head** of a series is its highest visible member. When two visible members cannot be ordered
and nothing outranks both, each of them counts as the head.

### Precedence

Versions order by core first, then by build metadata. The variant takes no part in the order; it
is part of the series.

- **Core:** numeric, element by element. A longer core beats its own prefix: `25.0.0.1.1` beats `25.0.0.1`.
- **Build metadata, at equal core**, lowest first:
  1. `+ea.N`, exactly `ea` and one numeric identifier, ordered by N.
  2. No build metadata.
  3. `+N`, `+N.N` and so on, numeric identifiers only, compared left to right numerically. A
     longer sequence beats its own prefix.
- **Any other build metadata** (`+r17`, `+1.r25`) is of an unknown kind. At equal core it cannot be
  ordered against different metadata, or against none.

Two versions are **equal** when they read the same. A post **beats** a stored row when it ranks
higher, or ranks equal while spelled differently. Stored `27.0.0+35` and posted `27.0.0.0+35` read
the same, so the post wins.

These examples drive comparator unit tests.

- Ordered, lowest first: `25.0.0.0+ea.1`, `25.0.0.0+ea.2`, `25.0.0.0`, `25.0.0.0+1`, `25.0.0.0+1.1`,
  `25.0.0.0+1.2`, `25.0.0.0+1.10`, `25.0.0.0+2`, `25.0.0.1`, `25.0.0.1.1`, `25.0.1.0+ea.3`
- Ordered, lowest first: `28.0.0.0+ea.20`, `28.0.0.0`, `28.0.0.0+1`
- Ordered across series: `27.0.0.0+35` below `28.0.0.0+ea.1`
- Ordered by core despite unknown metadata: `22.1.0.1+r17` below `22.1.0.2+1`
- Equal: `27.0.0+35` and `27.0.0.0+35`; `17.0.20` and `17.0.20.0`; `26.0.2.fx` and `26.0.2.0-fx`
- Cannot be ordered: `22.1.0.1+r17` and `22.1.0.1+1`; `22.1.0.1+r17` and `22.1.0.1`;
  `22.1.0.1+r17` and `22.1.0.1+r21`

### Vendor posts

A vendor post is compared with the head of its series.

- **It beats the head, it is the head, or the series has no visible member.** The post is stored
  visible and is the head. Every other visible member of the series is hidden, so exactly one
  stays visible. Leftovers collapse here: Corretto major 8 on `LINUX_X64` shows `8.0.504`,
  `8.0.472` and `8.0.232`, and its next post that becomes the head leaves one.
- **It ranks below the head.** The post is stored hidden and gains no tag. Nothing else changes.
- **It cannot be ordered against the head.** `409 Conflict`, naming the head. No row or tag is
  written. State writes a `REFUSE` audit entry, attributed to the poster and naming the posted
  version and the head, outside the transaction like every audit entry.

When the series has more than one head, the post must beat every one to become the head. It is
refused if it cannot be ordered against any one of them, unless it is itself one of the heads:
then it is the head, and every other visible member is hidden.

A post of a row that already exists hidden, one the admin hid for instance, is treated like any
other post: if it beats the head, it is visible again. Every stored post updates the row's URL and
checksums as today.

### Tags

Tags move only when a vendor post is the head of a **plain** series. Each is scoped to the post's
distribution and platform. Moving a tag takes it off whichever row held it.

- **`<FEATURE>`** (for example `25`) moves to the head if the head beats the current holder, or
  there is no holder, or the holder is unreadable. So it sits on the highest head among the series
  sharing that feature: `21` goes to the interim head `21.5.0.0.1`, not the feature head `21.0.0.0.1`.
- **`lts`** moves to the head only if a row of the same series holds it, visible or hidden. So a
  vendor post never takes `lts` out of its series, and an `lts` left on a hidden row heals on the
  series' next post. LTS lives on one series per distribution and platform (25 today, 29
  later); moving it to another series is an admin action. An interim post never hides the feature
  line's head, so `lts` stays on `21.0.x.x`.
- **`latest`** moves to the head if the head beats the current holder, or there is no holder, or
  the holder is unreadable. If the head cannot be ordered against the holder, `latest` stays. So
  `latest` tracks the highest plain head, early access included: OpenJDK's `28.0.0.0+ea.20` holds
  it over the GA `27.0.0+35`.

A post that is not the head, and any post into an `fx` or `crac` series, moves no tag. The posted
row keeps any tag it already held.

### Admin override

An admin post skips the comparator and moves nothing implicitly.

- **`visible` true, or absent:** the posted row becomes the only visible row of its series. Every
  other visible member is hidden, whatever its order.
- **`visible` false:** only the posted row is hidden. The admin may hide a series' only visible row.
- **`tags`:** the posted row ends up holding exactly the tags listed. A listed tag moves onto it
  from whichever row held it. A tag it held but did not list is removed. Absent `tags` means no
  tags. No other tag moves, so the admin may leave a tag on a row it hides, or strip a row's `lts`.

A legacy admin post is stored with its visibility and tags as above, but hides nothing. Once
stored, it is read like any other stored row.

### Writes

A post's upsert, hides and tag moves commit together or not at all. Java writes for the same
distribution and platform take effect one at a time, whoever sends them: `POST /versions/java`,
`DELETE /versions/java`, and the admin's `POST /versions/tags` and `DELETE /versions/tags` for
java. So two concurrent writes never leave two visible members in a series and never lose a tag
move. The scope is distribution
and platform, not series, because `latest` spans series.

Each row a post hides gets its own `RETIRE` audit entry, attributed to the caller that posted. The
posted row's `CREATE` entry records the visibility and tags it was stored with. Audit writes stay
outside the transaction, as today: a failed audit write is logged and never rolls back a post.

## API Contract

### `POST /versions/java`

Authenticated. The candidate is fixed by the route, so the body has no `candidate` field.

| Field | Required | Description |
|---|---|---|
| `version` | yes | Matches the grammar. The admin may also send a legacy version |
| `platform` | yes | A `Platform` value |
| `url` | yes | An `https` URL |
| `distribution` | no | A `Distribution` value |
| `md5sum`, `sha256sum`, `sha512sum` | no | As on `POST /versions` |
| `visible` | no | Admin only. Absent means `true` |
| `tags` | no | Admin only. Absent means no tags. Each tag follows the existing tag rules |

```json
{
  "version": "25.0.5.0+3",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B3/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_3.tar.gz"
}
```

| Status | Body | When |
|---|---|---|
| `204 No Content` | empty | Stored: as the head, as a hidden row, or as the admin posted it |
| `400 Bad Request` | `ValidationErrorResponse` | A field fails validation; a vendor's version fails the grammar; a non-admin body carries `visible` or `tags` (the error names the field); the body carries `candidate` |
| `401 Unauthorized` | empty | Missing or invalid token |
| `403 Forbidden` | `ErrorResponse` | The community role, a vendor without `java` in scope, or an unknown role, whatever the body |
| `409 Conflict` | `ErrorResponse` | A vendor post cannot be ordered against the series head; the message names the head's version |
| `500 Internal Server Error` | `ErrorResponse` | Database failure, or the candidate registry has never loaded, as on `POST /versions` |

A grammar failure identifies the `version` field and describes the format as
`FEATURE.INTERIM.UPDATE.PATCH[.N...][-fx|-crac][+build-metadata]`, as today.

### `DELETE /versions/java`

Authenticated. Deletes one java row, as `DELETE /versions` does today for every candidate. The body
is `version`, `platform` and an optional `distribution`, with no `candidate` field. `version` is any
stored string, legacy spellings included.

The admin and a vendor with `java` in scope may call it; everyone else gets `403`. Statuses are
those of `DELETE /versions` today: `204` deleted, `404` no such row, `409` the row is tagged. A
delete promotes nothing.

### Other routes

| Route | Change |
|---|---|
| `POST /versions` | `candidate` `java` is rejected with `400` for every caller. The error identifies `candidate` and names `POST /versions/java`. Other candidates are unchanged |
| `POST /versions/tags`, `DELETE /versions/tags` | `candidate` `java` is refused with `403` for every role but the admin. The admin and other candidates are unchanged |
| `DELETE /versions` | `candidate` `java` is rejected with `400` for every caller. The body is the route's existing `ErrorResponse`; its message names `candidate` `java` and `DELETE /versions/java`. Other candidates are unchanged |
| `POST /validate/versions` | Twin of `POST /versions`: a `java` body gets the identical `400`, identifying `candidate` and naming `POST /versions/java`. `POST /versions/java` has no twin |
| Read routes | Unchanged |

### Access, for java

| Route | Admin | Vendor with `java` | Vendor without `java` | Community |
|---|---|---|---|---|
| `POST /versions/java` | override | publish | `403` | `403` |
| `POST /versions` | `400` | `400` | `400` | `400` |
| `POST /versions/tags`, `DELETE /versions/tags` | yes | `403` | `403` | `403` |
| `DELETE /versions/java` | yes | yes | `403` | `403` |
| `DELETE /versions` | `400` | `400` | `400` | `400` |

This amends [`community-role.md`](community-role.md) for java: the rows of its API contract, its
§Behaviour sentence leaving java to `sdkman-contrib`, and its out-of-scope entry on refusing java
to the community role.

## Business Rules

### Scope

1. **Java has its own route.** `POST /versions/java` is the only route that writes a java version.
   `POST /versions` rejects `java` with `400`, naming the new route.
2. **The caller decides the behaviour.** A vendor with `java` in scope publishes under the
   comparator. The admin overrides. Every other role is refused with `403`.
3. **Vendors send neither `visible` nor `tags`.** Either one in a non-admin body is a `400` naming
   the field, and nothing is written.
4. **No feature flag.** Semverish is the java route's grammar, always enforced for vendors. Only the
   admin may post a legacy version.
5. **Other candidates are untouched.** No rule here applies to them, on any route.

### Series and order

6. **A series is (distribution, platform, feature, release line, variant)**, over readable rows
   only. The release line is the feature line when INTERIM is `0`, the interim line otherwise.
7. **Stored rows are read, never rewritten.** A three-component core reads as four; a legacy
   `.fx` or `.crac` reads as `-fx` or `-crac`. A row still outside the grammar is unreadable: never
   hidden by a post, never blocking one.
8. **Core first.** Numeric, element by element. A longer core beats its own prefix.
9. **Then build metadata.** `+ea.N` by N, below no metadata, below numeric builds compared left to
   right numerically.
10. **Unknown build metadata cannot be ordered** against different metadata at equal core.
11. **Equal reading, different spelling: the post wins.** The stored spelling is hidden.

### Vendor posts

12. **A post that beats the head, is the head, or fills an empty series is the only visible member.**
    Every other visible member of the series is hidden.
13. **A post below the head is stored hidden.** `204`. It gains no tag and nothing else changes.
14. **A post that cannot be ordered against the head is refused.** `409`, naming the head. No row
    or tag is written; a `REFUSE` audit entry records the refusal.
15. **Idempotent.** Re-posting the head keeps it the head. Posting it again hides nothing; its
    URL, checksums and `CREATE` audit entry are written as for any stored post.
16. **A hidden row can come back.** Re-posting it is like any other post.

### Tags

17. **Only a plain head moves tags**, scoped to its distribution and platform. `fx` and `crac`
    posts move none.
18. **`<FEATURE>` goes to the highest head among series sharing that FEATURE.**
19. **`lts` moves only within its series.** It goes to the head from any row of the same series,
    visible or hidden. A vendor post never moves it to another series.
20. **`latest` goes to the highest plain head**, early access included.
21. **Java tag calls are admin-only.** `POST /versions/tags` and `DELETE /versions/tags` refuse java
    to every other role with `403`.

### Admin override

22. **No comparator.** `visible` true, or absent, makes the posted row its series' only visible row.
    `visible` false hides only the posted row.
23. **Tags are literal.** The posted row ends with exactly the listed tags; absent means none.
    No other tag moves.
24. **A legacy admin post hides nothing.** It is stored as given.

### Writes

25. **Atomic.** A post's upsert, hides and tag moves commit together or not at all.
26. **One at a time per distribution and platform.** Every java write, posts, deletes and the
    admin's tag calls alike, takes its turn. Concurrent writes never leave two visible
    members in a series and never lose a tag move.
27. **Audited, best-effort.** One `RETIRE` entry per hidden row, attributed to the poster, written
    outside the transaction.
28. **Java deletes have their own route.** `DELETE /versions/java` behaves as `DELETE /versions`
    does today, for the admin and a vendor with `java` in scope only; `DELETE /versions` rejects
    java with `400`. Deleting promotes nothing: deleting a head leaves its
    series without a visible member until the next post. A tagged row still cannot be deleted
    (`409`), and for java only the admin can remove its tags first.
29. **No backlog migration.** A series converges on its next post. A dormant series stays as it is.

## Findings

- **The positional rule hides newer builds.** On 2026-10-19 OpenJDK on `LINUX_X64` advertises
  `26.0.0.0+35`, `24.0.1.0+9`, `23.0.0.0+37` and `22.0.0.0+36`, while `26.0.2.0+10`, `24.0.2.0+12`,
  `23.0.2.0+7` and `22.0.2.0+9` are hidden. Rule 29 leaves them until DISCO next posts into each
  series.
- **Every live tag sits on a visible row.** On `LINUX_X64`, Temurin holds `lts` and `25` on
  `25.0.4.1+1` and `27` and `latest` on `27.0.0.0+35`. OpenJDK holds `27` on `27.0.0+35`, and `28`
  and `latest` on `28.0.0.0+ea.20`.
- **Most majors carry no `<FEATURE>` tag today.** Temurin has none for 8, 11, 17, 21 or 26. Rule 18
  adds them as posts arrive.
- **The admin's legacy post is recorded as a decision.** [`docs/decisions/0011`](../../../docs/decisions/0011-admin-edits-legacy-java.md)
  supersedes 0004, which had legacy rows edited by direct SQL. The route also gives F3 of the [archived observation window](../../../archive/java-list-supersession/README.md)
  a remedy: a legacy `.rN` twin stays unreadable, so no vendor post hides it, but the admin can.

## Consumers

- **DISCO.** It posts java to `POST /versions` with `visible` set, and moves tags through the tag
  endpoints. All three break the day this ships: the generic route answers `400`, a vendor body
  carrying `visible` or `tags` answers `400`, and the tag endpoints answer `403`. DISCO must post
  to `POST /versions/java`, delete through `DELETE /versions/java`, and stop sending `visible` and
  tags. There is no transition window: the
  State merge and DISCO's switch are timed together, and any publication DISCO misses in between is
  re-posted on its next sweep. DISCO treats `409` as final for that build: it skips the build and
  continues the sweep.
- **The control plane's local stack.** `seed/seed.sh` posts java through `POST /versions`, with
  three-component versions the grammar rejects. On the java route, as the admin, they are legacy
  posts. The `SEMVERISH_VALIDATION_ENABLED` line of `compose/compose.yaml` and
  `migration/tests/e2e/semverish-toggle.sh` exercise the removed flag. One control-plane change,
  landed with the submodule bump that ships this spec, deletes the toggle script and its step in the
  verification gate, drops the compose line, moves the seed's java posts to `POST /versions/java`
  as the admin, adds a decision superseding 0003 that records that State orders DISCO's java posts
  and places `lts`, `latest` and `<FEATURE>`, and amends `docs/contracts.md` §4.3 to match.

## Examples

```gherkin
Feature: Java supersession on POST /versions/java

  Background:
    Given DISCO is a vendor with "java" in scope
      And every java row below is on LINUX_X64

  # --- ordering ---

  Scenario: A newer build becomes the head and takes its tags
    Given TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
    When DISCO posts TEMURIN 25.0.5.0+3
    Then the response status is 204
      And TEMURIN 25.0.5.0+3 is visible and tagged "lts" and "25"
      And TEMURIN 25.0.4.1+1 is hidden and untagged
      And TEMURIN 25.0.4.1+1 has a RETIRE audit entry attributed to DISCO

  Scenario: An older build is stored hidden
    Given TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
    When DISCO posts TEMURIN 25.0.4.0+9
    Then the response status is 204
      And TEMURIN 25.0.4.0+9 is hidden and untagged
      And TEMURIN 25.0.4.1+1 is still visible and tagged "lts" and "25"

  Scenario: A GA build supersedes its early-access build
    Given OPENJDK 28.0.0.0+ea.20 is visible and tagged "28" and "latest"
    When DISCO posts OPENJDK 28.0.0.0+35
    Then OPENJDK 28.0.0.0+35 is visible and tagged "28" and "latest"
      And OPENJDK 28.0.0.0+ea.20 is hidden

  Scenario: An early-access build never supersedes its GA
    Given OPENJDK 28.0.0.0+35 is visible
    When DISCO posts OPENJDK 28.0.0.0+ea.21
    Then the response status is 204
      And OPENJDK 28.0.0.0+ea.21 is hidden
      And OPENJDK 28.0.0.0+35 is still visible

  Scenario: Build numbers compare numerically
    Given LIBERICA 21.0.12+1.10 is visible
    When DISCO posts LIBERICA 21.0.12.0+1.9
    Then LIBERICA 21.0.12.0+1.9 is hidden
      And LIBERICA 21.0.12+1.10 is still visible

  Scenario: A post that cannot be ordered against the head is refused
    Given KONA 21.0.9.0+r17 is visible
    When DISCO posts KONA 21.0.9.0+1
    Then the response status is 409
      And the error names "21.0.9.0+r17"
      And KONA 21.0.9.0+1 does not exist
      And KONA 21.0.9.0+r17 is still visible

  # --- reading stored rows ---

  Scenario: A legacy spelling of the same build gives way to the post
    Given OPENJDK 27.0.0+35 is visible and tagged "27"
    When DISCO posts OPENJDK 27.0.0.0+35
    Then OPENJDK 27.0.0.0+35 is visible and tagged "27"
      And OPENJDK 27.0.0+35 is hidden

  Scenario: A three-component row is read as four
    Given TEMURIN 17.0.20 is visible
    When DISCO posts TEMURIN 17.0.20.0+8
    Then TEMURIN 17.0.20.0+8 is visible
      And TEMURIN 17.0.20 is hidden

  Scenario: A legacy dot variant is read as a variant
    Given LIBERICA 17.0.19.crac is visible
    When DISCO posts LIBERICA 17.0.20.0-crac+1
    Then LIBERICA 17.0.20.0-crac+1 is visible
      And LIBERICA 17.0.19.crac is hidden

  Scenario: Unreadable rows are left alone
    Given LIBERICA_NIK 25.0.4.r25 and 25.0.1.0+1 are visible
    When DISCO posts LIBERICA_NIK 25.0.5.0+1
    Then LIBERICA_NIK 25.0.5.0+1 is visible
      And LIBERICA_NIK 25.0.1.0+1 is hidden
      And LIBERICA_NIK 25.0.4.r25 is still visible

  # --- series boundaries ---

  Scenario: A variant series supersedes but is never tagged
    Given LIBERICA 21.0.12-fx+1.1 and 21.0.12+1.1 are visible
    When DISCO posts LIBERICA 21.0.13.0-fx+1
    Then LIBERICA 21.0.13.0-fx+1 is visible and untagged
      And LIBERICA 21.0.12-fx+1.1 is hidden
      And LIBERICA 21.0.12+1.1 is still visible

  Scenario: A crac post leaves the plain and fx series alone
    Given LIBERICA 17.0.20-crac+1.2, 17.0.20-fx+1.1 and 17.0.20+1.1 are visible
    When DISCO posts LIBERICA 17.0.21.0-crac+1
    Then LIBERICA 17.0.20-crac+1.2 is hidden
      And LIBERICA 17.0.20-fx+1.1 and 17.0.20+1.1 are still visible

  Scenario: Leftover visible rows collapse on the next post
    Given CORRETTO 8.0.504, 8.0.472 and 8.0.232 are visible
    When DISCO posts CORRETTO 8.0.512.0+1
    Then CORRETTO 8.0.512.0+1 is the only visible CORRETTO row of major 8
      And CORRETTO 8.0.504, 8.0.472 and 8.0.232 each have a RETIRE audit entry

  # --- tags ---

  Scenario: lts does not follow a post into another series
    Given TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
    When DISCO posts TEMURIN 29.0.0.0+ea.1
    Then TEMURIN 29.0.0.0+ea.1 is tagged "29"
      And TEMURIN 29.0.0.0+ea.1 is not tagged "lts"
      And TEMURIN 25.0.4.1+1 is still visible and tagged "lts" and "25"

  Scenario: lts left on a hidden row moves to the next head
    Given TEMURIN 25.0.4.1+1 is hidden and tagged "lts"
      And TEMURIN 25.0.3.0 is visible and tagged "25"
    When DISCO posts TEMURIN 25.0.5.0+1
    Then TEMURIN 25.0.5.0+1 is visible and tagged "lts" and "25"
      And TEMURIN 25.0.4.1+1 is untagged

  Scenario: latest moves to a higher series, early access included
    Given TEMURIN 27.0.0.0+35 is visible and tagged "27" and "latest"
    When DISCO posts TEMURIN 28.0.0.0+ea.5
    Then TEMURIN 28.0.0.0+ea.5 is visible and tagged "28" and "latest"
      And TEMURIN 27.0.0.0+35 is still visible and tagged "27"

  Scenario: latest stays on a higher series
    Given OPENJDK 28.0.0.0+ea.20 is visible and tagged "28" and "latest"
      And OPENJDK 27.0.0+35 is visible and tagged "27"
    When DISCO posts OPENJDK 27.0.1.0+3
    Then OPENJDK 27.0.1.0+3 is visible and tagged "27"
      And OPENJDK 28.0.0.0+ea.20 is still tagged "latest"

  Scenario: A major tag reaches a series that never had one
    Given TEMURIN 21.0.12+1.1 is visible and untagged
    When DISCO posts TEMURIN 21.0.13.0+1
    Then TEMURIN 21.0.13.0+1 is tagged "21"
      And TEMURIN 21.0.13.0+1 is not tagged "lts"

  Scenario: An interim release leaves the feature line visible
    Given GRAALVM 21.0.0.0.1 is visible and tagged "lts" and "21"
    When DISCO posts GRAALVM 21.5.0.0.1
    Then GRAALVM 21.5.0.0.1 is visible and tagged "21"
      And GRAALVM 21.0.0.0.1 is still visible and tagged "lts"

  Scenario: A newer interim release hides the previous one
    Given GRAALCE 25.3.4+1.r25 is visible
    When DISCO posts GRAALCE 25.4.1.0
    Then GRAALCE 25.4.1.0 is visible
      And GRAALCE 25.3.4+1.r25 is hidden

  # --- revival and idempotence ---

  Scenario: A hidden row comes back when it beats the head
    Given TEMURIN 25.0.4.1+1 is hidden
      And TEMURIN 25.0.4.0+9 is visible and tagged "lts" and "25"
    When DISCO posts TEMURIN 25.0.4.1+1
    Then TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
      And TEMURIN 25.0.4.0+9 is hidden

  Scenario: Re-posting the head is idempotent
    Given TEMURIN 25.0.4.1+1 is the only visible TEMURIN row of major 25, tagged "lts" and "25"
    When DISCO posts TEMURIN 25.0.4.1+1 twice
    Then TEMURIN 25.0.4.1+1 is still visible and tagged "lts" and "25"
      And no RETIRE audit entry is written

  # --- vendor limits ---

  Scenario: A vendor may not send tags
    When DISCO posts TEMURIN 25.0.5.0+3 with tags "lts"
    Then the response status is 400
      And the validation error identifies the "tags" field
      And TEMURIN 25.0.5.0+3 does not exist

  Scenario: A vendor may not send visible
    When DISCO posts TEMURIN 25.0.5.0+3 with visible true
    Then the response status is 400
      And the validation error identifies the "visible" field
      And TEMURIN 25.0.5.0+3 does not exist

  Scenario: A vendor's version must match the grammar
    When DISCO posts TEMURIN 25.0.5
    Then the response status is 400
      And the validation error identifies the "version" field

  Scenario: A vendor without java in scope is refused
    Given a vendor whose scope is "kotlin"
    When it posts TEMURIN 25.0.5.0+3 to POST /versions/java
    Then the response status is 403

  Scenario: The community role is refused
    When the community role posts TEMURIN 25.0.5.0+3 to POST /versions/java
    Then the response status is 403

  Scenario: A vendor may not move java tags
    Given TEMURIN 25.0.4.1+1 is visible
    When DISCO assigns the tag "lts" to TEMURIN 25.0.4.1+1 through POST /versions/tags
    Then the response status is 403

  Scenario: A vendor may not remove java tags
    Given TEMURIN 25.0.4.1+1 is tagged "lts"
    When DISCO removes the tag "lts" from TEMURIN through DELETE /versions/tags
    Then the response status is 403
      And TEMURIN 25.0.4.1+1 is still tagged "lts"

  Scenario: The admin may still move java tags
    Given TEMURIN 25.0.4.1+1 is visible
    When the admin assigns the tag "lts" to TEMURIN 25.0.4.1+1 through POST /versions/tags
    Then the response status is 204

  # --- admin override ---

  Scenario: The admin's post wins by position, with tags as posted
    Given TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
    When the admin posts TEMURIN 25.0.4.0+9 with tags "lts"
    Then TEMURIN 25.0.4.0+9 is visible and tagged "lts" only
      And TEMURIN 25.0.4.1+1 is hidden and tagged "25" only

  Scenario: The admin can hide a series' only visible row
    Given TEMURIN 25.0.4.1+1 is the only visible TEMURIN row of major 25, tagged "lts" and "25"
    When the admin posts TEMURIN 25.0.4.1+1 with visible false and tags "25"
    Then TEMURIN 25.0.4.1+1 is hidden and tagged "25" only
      And no TEMURIN row of major 25 is visible

  Scenario: An admin post without tags leaves the row untagged
    Given TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
    When the admin posts TEMURIN 25.0.4.1+1 without tags
    Then TEMURIN 25.0.4.1+1 is visible and untagged

  Scenario: The admin can hide a legacy row
    Given LIBERICA_NIK 25.0.4.r25 is visible
    When the admin posts LIBERICA_NIK 25.0.4.r25 with visible false
    Then the response status is 204
      And LIBERICA_NIK 25.0.4.r25 is hidden

  Scenario: A legacy admin post hides nothing
    Given TEMURIN 25.0.4.1+1 is visible
    When the admin posts TEMURIN 25.0.3
    Then the response status is 204
      And TEMURIN 25.0.3 is visible
      And TEMURIN 25.0.4.1+1 is still visible

  # --- routes ---

  Scenario: The generic route refuses java
    When the admin sends POST /versions with candidate "java" and version "25.0.5.0+3"
    Then the response status is 400
      And the validation error identifies the "candidate" field
      And its message names POST /versions/java

  Scenario: The generic delete refuses java
    When the admin sends DELETE /versions with candidate "java" and version "25.0.4.1+1"
    Then the response status is 400
      And its message names DELETE /versions/java

  Scenario: DISCO deletes an untagged java row
    Given TEMURIN 25.0.3.0 is hidden and untagged
    When DISCO sends DELETE /versions/java for TEMURIN 25.0.3.0
    Then the response status is 204

  Scenario: The community role may not delete java
    When the community role sends DELETE /versions/java for TEMURIN 25.0.3.0
    Then the response status is 403

  Scenario: Other candidates are unaffected
    Given gradle 8.13 on UNIVERSAL is visible
    When a vendor with "gradle" in scope posts gradle 8.14 on UNIVERSAL to POST /versions
    Then the response status is 204
      And gradle 8.13 is still visible
```

## Out of Scope

- **A backlog migration.** Series converge as DISCO posts into them (Rule 29).
- **Tags on `fx` and `crac` series.**
- **Choosing the LTS series.** Moving `lts` to a new series is the admin's call.
- **Delete semantics.** `DELETE /versions/java` moves java deletes, it does not change them.
- **The read routes**, and whether listings or `findByTag` filter on `visible`.
- **DISCO's and the control plane's changes.** They are named under §Consumers and are their own work.
- **Any candidate other than java.**

## Acceptance Criteria

- [ ] Every scenario in §Examples passes as an acceptance test
- [ ] The comparator orders, equates and refuses every pair exactly as §Precedence lists them
- [ ] The normalisations read every example in §Reading stored rows as stated, and `25.0.4.r25`,
      `25.r25` and `16.ea.36` are unreadable
- [ ] `POST /versions/java` accepts the request body of §API Contract and answers each status in its table
- [ ] After a vendor post stored as the head, and after an admin post of a grammar-valid version with `visible` true or absent, the posted row's series has exactly one visible member
- [ ] A vendor post moves `<FEATURE>`, `lts` and `latest` exactly as §Tags states, and moves no tag from an `fx` or `crac` post
- [ ] An admin post leaves the posted row with exactly the listed tags and moves no other tag
- [ ] `POST /versions` rejects `java` with `400` for every caller, identifying `candidate` and naming `POST /versions/java`; other candidates see no change and get no version-shape validation
- [ ] `DELETE /versions/java` deletes as `DELETE /versions` does today, for the admin and a vendor with `java` in scope, and answers `403` to everyone else
- [ ] `DELETE /versions` rejects `java` with `400` for every caller, as the route's existing `ErrorResponse` whose message names `candidate` `java` and `DELETE /versions/java`; other candidates see no change
- [ ] `POST /versions/tags` and `DELETE /versions/tags` refuse `java` with `403` to every role but the admin; the admin and other candidates see no change
- [ ] The community role, a vendor without `java` in scope and an unknown role get `403` from `POST /versions/java`
- [ ] Concurrent posts for one distribution and platform leave one visible member per series, lose no tag move, and leave `latest` on the highest plain head
- [ ] A failure in a post's upsert, hides or tag moves leaves nothing of it written
- [ ] Each hidden row produces a `RETIRE` audit entry attributed to the poster; an audit failure does not roll back the post
- [ ] The positional rule is gone: no route hides a java row except as this spec states
- [ ] `validation.semverish.enabled` is gone from `application.conf` and `SEMVERISH_VALIDATION_ENABLED` is no longer read
- [ ] `src/main/resources/openapi/documentation.yaml` documents `POST /versions/java` (request schema, who may call it, the comparator's effect on visibility and tags, every status including `409`), `DELETE /versions/java`, the `400` for `java` on `POST /versions` and `DELETE /versions`, the `403` for `java` on the tag routes, and drops the feature-flag wording
- [ ] All quality gates pass (`./gradlew check`: build, detekt, ktlint, tests)
