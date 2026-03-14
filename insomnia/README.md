# SDKMAN State API - Insomnia Collection

This directory contains an Insomnia API test collection for the SDKMAN State API. The collection provides comprehensive coverage of all API endpoints including versions management, tags, health checks, and various validation scenarios.

## Importing the Collection

1. Open Insomnia
2. Click the **Import** button (or use `Ctrl+I` / `Cmd+I`)
3. Select **Import From File**
4. Navigate to this directory and select `sdkman-state-api.json`
5. Click **Import**

The collection will be imported as "SDKMAN State API" with all requests organized by feature.

## Environment Setup

The collection includes two pre-configured environments:

### Local Environment
- **base_url**: `http://localhost:8080`
- **username**: `testuser`
- **password**: `password123`

Use this environment when testing against a locally running instance of the SDKMAN State API.

### Deployed Environment
- **base_url**: `https://state.sdkman.io`
- **username**: (empty - set your credentials)
- **password**: (empty - set your credentials)

Use this environment when testing against the deployed production/staging API.

### Environment Variables

| Variable | Description | Default (Local) |
|----------|-------------|-----------------|
| `base_url` | Base URL of the API | `http://localhost:8080` |
| `username` | Basic Auth username for write operations | `testuser` |
| `password` | Basic Auth password for write operations | `password123` |

### Switching Environments

1. Click the environment dropdown in the top-left of Insomnia
2. Select either "Local" or "Deployed"
3. For the Deployed environment, click **Manage Environments** to set your credentials

## Collection Structure

The collection is organized by feature, not HTTP method:

```
SDKMAN State API/
├── 1. Health Check
│   └── Health Check - Database Available
├── 2. Get Version List
│   ├── Get All Versions for Candidate
│   ├── Get Visible Versions Only
│   ├── Get Hidden Versions Only
│   ├── Get All Versions (Visible and Hidden)
│   ├── Get Universal Platform Versions
│   ├── Get Linux ARM64 Versions
│   ├── Get Linux x64 Versions
│   ├── Get Universal Visible Versions
│   ├── Get Universal Hidden Versions
│   └── Get Versions with Tags
├── 3. Get Single Version
│   ├── Get Universal Version
│   ├── Get Platform-Specific Version
│   ├── Get Version Without Distribution
│   ├── Get Version with Tags
│   ├── Get Non-Existent Version - 404
│   ├── Get Platform-Specific Version Not Found - 404
│   └── Get Version Empty Candidate - 400
├── 4. Create Version
│   ├── Create Version with Distribution
│   ├── Create Version Without Distribution
│   ├── Create Version with -RC1 Suffix
│   ├── Create Hidden Version (visible=false)
│   ├── Create Version with All Checksums
│   ├── Create Version with Tags
│   ├── Create Version with Tags (No Distribution)
│   ├── Create Version Invalid Distribution - 400
│   ├── Create Version Multiple Invalid Fields - 400
│   ├── Create Version Missing Required Fields - 400
│   ├── Create Version Invalid Tag Characters - 400
│   ├── Create Version Blank Tag - 400
│   ├── Create Version Tag Too Long - 400
│   ├── Create Version Tag Starts with Dot - 400
│   └── Create Version No Authentication - 401
├── 5. Idempotent Version Updates
│   ├── 1. First POST - Create Version
│   ├── 2. Second POST - Idempotent (Same Data)
│   ├── 3. Third POST - Update Existing Version
│   └── 4. Idempotent Without Distribution
├── 6. Delete Version
│   ├── Delete Version with Distribution
│   ├── Delete Version Without Distribution
│   ├── Delete Version with -RC1 Suffix
│   ├── Delete Version Empty Candidate - 400
│   ├── Delete Version Empty Version - 400
│   ├── Delete Non-Existent Version - 404
│   ├── Delete Version Invalid Distribution - 400
│   ├── Delete Version Malformed JSON - 400
│   └── Delete Version No Authentication - 401
├── 7. Delete Tagged Versions
│   ├── Delete Tagged Version - 409 Conflict
│   └── Delete Untagged Version - 204
└── 8. Delete Tags
    ├── Delete Tag - Success
    ├── Delete Tag Without Distribution
    ├── Delete Non-Existent Tag - 404
    ├── Delete Tag Blank Candidate - 400
    ├── Delete Tag Blank Tag Name - 400
    ├── Delete Tag Invalid Distribution - 400
    ├── Delete Tag Invalid Platform - 400
    ├── Delete Tag Multiple Validation Errors - 400
    ├── Delete Tag No Authentication - 401
    └── Delete Tag Malformed JSON - 400
```

## Test Execution Order

For a complete test workflow, execute requests in the following order:

### 1. Verify Service Health
- Run **Health Check - Database Available** to ensure the API is running

### 2. Create Test Data
Execute the following POST requests to set up test versions:
- **Create Version with Distribution** - Creates `java/17.0.1` on MAC_X64
- **Create Version Without Distribution** - Creates `maven/3.9.0` UNIVERSAL
- **Create Version with Tags** - Creates `java/27.0.2` with tags

### 3. Test Read Operations
- Run various GET requests from "Get Version List" and "Get Single Version"
- Verify filtering by platform, visibility, and distribution

### 4. Test Idempotent Updates
Execute in order:
1. **1. First POST - Create Version**
2. **2. Second POST - Idempotent (Same Data)** - Should return 204
3. **3. Third POST - Update Existing Version** - Should update and return 204

### 5. Test Tag Operations
- Create a version with tags
- Verify tags appear in GET responses
- Test tag deletion via DELETE /versions/tags
- Verify 409 Conflict when deleting version with active tags

### 6. Clean Up Test Data
Delete test versions in reverse order:
- First remove tags if present
- Then delete versions

### 7. Validation Tests
Test error handling scenarios:
- Run requests marked with `- 400` suffix for validation errors
- Run requests marked with `- 401` suffix for authentication errors
- Run requests marked with `- 404` suffix for not found scenarios
- Run requests marked with `- 409` suffix for conflict scenarios

## Test Data Considerations

### Prerequisites
1. **PostgreSQL**: Ensure PostgreSQL is running and accessible
   ```bash
   docker run --restart=always \
       --name postgres \
       -p 5432:5432 \
       -e POSTGRES_USER=postgres \
       -e POSTGRES_PASSWORD=postgres \
       -e POSTGRES_DB=sdkman \
       -d postgres
   ```

2. **Application**: Start the SDKMAN State API
   ```bash
   ./gradlew run
   ```

### Data Isolation

- Each test should ideally be run against a clean database
- The collection does not automatically clean up data
- For integration testing, consider:
  - Running database migrations before tests
  - Truncating tables between test runs
  - Using unique version identifiers per test run

### Idempotency

POST operations are idempotent:
- Posting the same version twice returns 204 both times
- Posting with updated fields overwrites the existing version
- This allows safe re-running of test sequences

### Tags and Deletion

Versions with active tags cannot be deleted:
- Delete all tags first using DELETE /versions/tags
- Then delete the version using DELETE /versions
- The 409 Conflict response includes the list of active tags

## Valid Enum Values

### Platforms
Use lowercase in query parameters:
- `linuxx32`, `linuxx64`, `linuxarm32hf`, `linuxarm32sf`, `linuxarm64`
- `darwinx64`, `darwinarm64`
- `windowsx64`
- `universal`

Use uppercase in request bodies:
- `LINUX_X32`, `LINUX_X64`, `LINUX_ARM32HF`, `LINUX_ARM32SF`, `LINUX_ARM64`
- `MAC_X64`, `MAC_ARM64`
- `WINDOWS_X64`
- `UNIVERSAL`

### Distributions
- `BISHENG`, `CORRETTO`, `GRAALCE`, `GRAALVM`, `JETBRAINS`, `KONA`
- `LIBERICA`, `LIBERICA_NIK`, `MANDREL`, `MICROSOFT`, `OPENJDK`
- `ORACLE`, `SAP_MACHINE`, `SEMERU`, `TEMURIN`, `ZULU`

## Expected Response Codes

| Endpoint | Success | Auth Error | Validation Error | Not Found | Conflict |
|----------|---------|------------|------------------|-----------|----------|
| GET /meta/health | 200 | - | - | - | - |
| GET /versions/{candidate} | 200 | - | 400 | - | - |
| GET /versions/{candidate}/{version} | 200 | - | 400 | 404 | - |
| POST /versions | 204 | 401 | 400 | - | - |
| DELETE /versions | 204 | 401 | 400 | 404 | 409 |
| DELETE /versions/tags | 204 | 401 | 400 | 404 | - |

## Troubleshooting

### 401 Unauthorized
- Verify username and password are set in the environment
- Ensure Basic Auth is enabled on the request
- Check that credentials match the server configuration

### 404 Not Found
- Ensure test data has been created before running GET/DELETE requests
- Verify candidate, version, platform, and distribution match exactly

### 409 Conflict
- Version has active tags that must be removed first
- Use DELETE /versions/tags to remove tags before deleting version

### Connection Refused
- Verify the API server is running
- Check the base_url matches your server configuration
- Ensure PostgreSQL is running and accessible
