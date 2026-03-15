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

| Environment | Base URL | Auth |
|-------------|----------|------|
| **Local** | `http://localhost:8080` | testuser:password123 |
| **Deployed** | `https://state.sdkman.io` | Configure `username` and `password` |

### Default Credentials (Base Environment)

The collection includes default credentials that work out of the box for local testing:
- **base_url**: `http://localhost:8080`
- **username**: `testuser`
- **password**: `password123`

### Setting Up Deployed Environment

For the deployed environment, override the credentials:

1. In Insomnia, go to **Manage Environments**
2. Select **Deployed**
3. Add `username` and `password` keys with your actual credentials

## Test Flows

Each test flow is completely **self-contained** and **idempotent**:

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

## Running Tests with Inso CLI

### Prerequisites

Install the Inso CLI:

```bash
npm install -g insomnia-inso
```

### Run All Tests

```bash
# Run against local environment
inso run test "SDKMAN State API" --env "Local"

# Run against deployed environment (after configuring credentials)
inso run test "SDKMAN State API" --env "Deployed"
```

### Run Specific Test Flow

```bash
# Run only the health check test
inso run test "SDKMAN State API" --env "Local" --test-name-pattern "Health"

# Run all validation error tests
inso run test "SDKMAN State API" --env "Local" --test-name-pattern "Validation"
```

### Verbose Output

```bash
inso run test "SDKMAN State API" --env "Local" --verbose
```

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

### Authentication

Basic authentication is required for POST and DELETE operations.

**Default test credentials:**
- Username: `testuser`
- Password: `password123`
- Base64: `dGVzdHVzZXI6cGFzc3dvcmQxMjM=`

### Query Parameters

| Endpoint | Parameter | Values | Description |
|----------|-----------|--------|-------------|
| GET `/versions/*` | platform | linuxx64, darwinx64, etc. | Filter by platform |
| GET `/versions/*` | distribution | TEMURIN, CORRETTO, etc. | Filter by distribution |
| GET `/versions/*` | visible | true, false, all | Filter by visibility |

### Platform Values

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
