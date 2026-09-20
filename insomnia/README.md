# SDKMAN State API - Insomnia Collection

This directory contains an Insomnia v4 JSON collection for testing the SDKMAN State API. All tests are self-contained, meaning each test creates its own data, verifies it, and cleans up after itself.

## Files

- `sdkman-state-api.json` - Insomnia v4 export with all test flows

## Import into Insomnia

1. Open Insomnia
2. Go to **Application Menu** > **Preferences** > **Data** > **Import Data** > **From File**
3. Select `sdkman-state-api.json`
4. Select the workspace to import into

## Environments

The collection includes two environments:

| Environment | Base URL |
|-------------|----------|
| **Local** | `http://localhost:8080` |
| **Deployed** | `https://state.sdkman.io` |

### Credentials

**No credentials are committed.** The Base Environment ships `admin_password` empty and
you supply it yourself:

1. In Insomnia, go to **Manage Environments** > **Base Environment**
2. Set `admin_password`

| Variable | Committed value | Notes |
|----------|-----------------|-------|
| `admin_email` | `admin@sdkman.io` | The `admin.email` default from `application.conf` |
| `admin_password` | *(empty)* | Supply your own; never commit it |

Locally this is whatever you started the service with (`ADMIN_PASSWORD`, defaulting to the
`admin.password` value in `application.conf`). Against **Deployed** it is the real
production admin password — keep it out of any export you share, and note that an
Insomnia export includes environment values in plain text.

## Test Flows

Each test flow is completely **self-contained** and **idempotent**:

> The version flows write to whichever environment you point them at, and clean up after
> themselves. Under **Deployed** that is production. The candidate flows only ever touch
> the synthetic `insomniatest` identifier.

The `409` returned when deleting a candidate that still has versions is deliberately not
covered here: provoking it means publishing a version under a real candidate, which would
overwrite live registry metadata. `AdminCandidateDeletionAcceptanceSpec` covers it instead.

### 0. Authenticate
`POST /login` -> 200, stores the JWT as `bearerToken`. Every authenticated request also
fetches a token on demand, so this is for manual use.

### 1. Health Check
Simple standalone test - no setup or cleanup needed.
- GET `/meta/health` -> 200, status=SUCCESS

### 2. Test Universal Version
Tests creating/reading/deleting a version without distribution.
- POST scala/3.1.0 UNIVERSAL -> 204
- GET scala/3.1.0 -> 200, verify fields
- DELETE scala/3.1.0 -> 204

### 3. Test Platform-Specific Version
Tests platform and distribution fields.
- POST java/17.0.1 TEMURIN MAC_X64 -> 204
- GET java/17.0.1?platform=darwinx64&distribution=TEMURIN -> 200
- DELETE java/17.0.1 TEMURIN MAC_X64 -> 204

### 4. Test Version Visibility
Tests hidden versions (visible=false).
- POST gradle/8.0.0 UNIVERSAL visible=false -> 204
- GET gradle/8.0.0?visible=all -> 200, verify visible=false
- DELETE gradle/8.0.0 -> 204

### 5. Test Version with Tags
Tests tag creation and deletion workflow.
- POST java/21.0.1 TEMURIN LINUX_X64 tags=["latest","21"] -> 204
- GET java/21.0.1 -> 200, verify tags
- DELETE tag "latest" -> 204
- DELETE tag "21" -> 204
- DELETE java/21.0.1 -> 204

### 6. Test Delete Tagged Version Conflict
Tests that versions with tags cannot be deleted directly.
- POST kotlin/2.0.0 UNIVERSAL tags=["latest"] -> 204
- DELETE kotlin/2.0.0 -> **409 Conflict**
- DELETE tag "latest" -> 204
- DELETE kotlin/2.0.0 -> 204

### 7. Test POST Idempotency
Tests that POST is idempotent and can update existing versions.
- POST maven/3.9.0 UNIVERSAL url=original -> 204
- POST maven/3.9.0 UNIVERSAL url=original (same) -> 204
- POST maven/3.9.0 UNIVERSAL url=updated -> 204
- GET maven/3.9.0 -> 200, verify url=updated
- DELETE maven/3.9.0 -> 204

### 8. Validation Error Tests
Standalone tests for error responses - no cleanup needed.
- POST with invalid distribution -> 400
- POST with missing required fields -> 400
- POST with invalid tag characters -> 400
- POST without auth -> 401
- DELETE with empty candidate -> 400
- DELETE without auth -> 401
- DELETE non-existent version -> 404
- DELETE tag without auth -> 401
- DELETE non-existent tag -> 404

### 9. Test Candidate Registry
Registry lifecycle against the synthetic candidate `insomniatest`, which exists in no
environment and is removed by the final step.
- POST `/admin/candidates` insomniatest -> **201**
- POST the same identifier with a new name -> **200** (upsert, not a conflict)
- GET `/candidates` -> 200, verify the updated name, ascending order, and that `java`
  carries no `default`
- DELETE `/admin/candidates/insomniatest` -> 200, body is the removed record
- DELETE it again -> 404

### 10. Candidate Validation Errors
Standalone rejections - nothing is written, so no cleanup is needed.
- POST non-https `website_url` -> 400
- POST uppercase identifier -> 400
- POST non-ASCII description -> 400
- POST description containing a line break -> 400
- POST malformed JSON -> 400 `ValidationErrorResponse` (never a 500)
- POST without auth -> 401
- DELETE without auth -> 401
- DELETE unknown candidate -> 404

## Running Tests with Inso CLI

### Prerequisites

The `insomnia-inso` npm package is deprecated and `inso` is not in nixpkgs — use the
prebuilt binary from the Kong release:

```bash
gh release download core@13.0.1 -R Kong/insomnia -p 'inso-linux-x64-*.tar.xz' -D inso-cli --clobber
tar xf inso-cli/inso-linux-x64-*.tar.xz -C inso-cli
```

### Run the collection

These are `afterResponseScript` assertions rather than Insomnia unit tests, so the
command is `run collection`, not `run test`:

```bash
inso run collection -w insomnia/sdkman-state-api.json -e Local --reporter tap --ci
```

`-w` points at the export file, `-e` selects the environment (`Local` or `Deployed`),
`--reporter` is one of `tap|list|spec|dot|min|progress`, and `--ci` disables prompts.

Set `admin_password` first — every authenticated request needs it, and the whole run
shares a single token.

### Run one folder

`inso` has no folder-exclude flag; include the folders you want by id:

```bash
inso run collection -w insomnia/sdkman-state-api.json -e Local \
  -i fld_candidate_registry -i fld_candidate_errors --reporter tap --ci
```

Last measured: **47/47 requests, 66/66 assertions** against `Local`.

## API Reference

### Endpoints

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/meta/health` | No | Health check |
| GET | `/versions/{candidate}` | No | List versions |
| GET | `/versions/{candidate}/{version}` | No | Get specific version |
| POST | `/versions` | Yes | Create/update version |
| DELETE | `/versions` | Yes | Delete version |
| DELETE | `/versions/tags` | Yes | Delete tag |
| POST | `/login` | No | Exchange admin credentials for a JWT |
| GET | `/candidates` | No | List the candidate registry |
| POST | `/admin/candidates` | Yes | Register a candidate (upsert) |
| DELETE | `/admin/candidates/{candidate}` | Yes | Delete a candidate |

### Authentication

**JWT bearer**, required for every POST and DELETE. The service implements no other
scheme — a Basic `Authorization` header is refused exactly like no header at all.

`POST /login` exchanges `admin_email` / `admin_password` for a token. Each authenticated
request carries a pre-request script that fetches one **once per run** and reuses it:

```javascript
if (!insomnia.variables.get("bearerToken")) {
  // POST /login, then insomnia.variables.set("bearerToken", ...)
}
```

The caching is not an optimisation. `/login` is rate limited to **5 attempts per minute
per IP**, and the limiter records every attempt including the successful ones, so a
token fetched per request would start returning `429` partway through a run.

Read `base_url` and the credentials with `insomnia.variables.get(...)`, not
`insomnia.environment.get(...)` — the latter sees only the active sub-environment, and
these live in the Base Environment, so they come back `undefined` under **Local**.

### Query Parameters

| Endpoint | Parameter | Values | Description |
|----------|-----------|--------|-------------|
| GET `/versions/*` | platform | `LINUX_X64`, `MAC_X64`, ... | **Required.** The enum name, not the platform id |
| GET `/versions/*` | distribution | TEMURIN, CORRETTO, etc. | Filter by distribution |
| GET `/versions/*` | visible | true, false, all | Filter by visibility |

### Platform Values

`GET /versions/{candidate}/{version}` takes the **enum value** in its `platform` query
parameter and rejects the platform id with a `400`. The ids below are what the API
returns in a response body, not what it accepts as input.

| Enum Value | Platform ID |
|------------|-------------|
| LINUX_X32 | linuxx32 |
| LINUX_X64 | linuxx64 |
| LINUX_ARM32HF | linuxarm32hf |
| LINUX_ARM32SF | linuxarm32sf |
| LINUX_ARM64 | linuxarm64 |
| MAC_X64 | darwinx64 |
| MAC_ARM64 | darwinarm64 |
| WINDOWS_X64 | windowsx64 |
| UNIVERSAL | universal |

### Distribution Values

BISHENG, CORRETTO, GRAALCE, GRAALVM, JETBRAINS, KONA, LIBERICA, LIBERICA_NIK, MANDREL, MICROSOFT, OPENJDK, ORACLE, SAP_MACHINE, SEMERU, TEMURIN, ZULU

## Assertion Format

Tests use the Insomnia `afterResponseScript` format:

```javascript
insomnia.test('Status is 200', () => {
  insomnia.expect(insomnia.response.code).to.equal(200);
});

insomnia.test('Body contains expected value', () => {
  const body = JSON.parse(insomnia.response.body);
  insomnia.expect(body.candidate).to.equal('java');
  insomnia.expect(body.tags).to.be.an('array');
  insomnia.expect(body.tags).to.include('latest');
});
```

**Note:** Use `insomnia.response.code` for numeric status codes (200, 204, 400, etc.) and `insomnia.response.status` for status text ("OK", "No Content", etc.).
