# SDKMAN State API

This application exposes the SDKMAN Candidate and Version state through a JSON API.

It exposes `GET`, `POST`, `PATCH` and `DELETE` HTTP methods on candidates and versions.

The audience of this API is twofold:

* as a backend for the [native components](https://github.com/sdkman/sdkman-cli-native) written in Rust
* as admin API of the datastore, used directly or by build system plugins and
  the [DISCO integration](https://github.com/sdkman/sdkman-disco-integration)

## Running Locally

1. Start PostgreSQL database:
   ```bash
   docker run --restart=always \
       --name postgres \
       -p 5432:5432 \
       -e POSTGRES_USER=postgres \
       -e POSTGRES_PASSWORD=postgres \
       -e POSTGRES_DB=sdkman \
       -d postgres
   ```

2. Run the service:
   ```bash
   ./gradlew run
   ```

The service will start on port 8080.

## Running Tests

```bash
./gradlew test
```

## API Usage Examples

### POST a new version (requires authentication):
```bash
http POST localhost:8080/versions \
    candidate=java \
    version=21.0.2 \
    platform=LINUX_X64 \
    url=https://download.oracle.com/java/21/archive/jdk-21.0.1_linux-x64_bin.tar.gz \
    visible:=true \
    --auth testuser:password123
```

### GET versions for a candidate:
```bash
http GET localhost:8080/versions/java
```

## Audit Table Query Scenarios

The audit table tracks download and usage events for SDKMAN candidates. The following indexes optimize common analytical queries:

### Download Count for a Candidate (Last 30 Days)
```sql
SELECT COUNT(*) as downloads
FROM audit
WHERE candidate = 'java'
  AND timestamp > NOW() - INTERVAL '30 days';
```
*Uses index:* `idx_audit_candidate_timestamp`

### Top 10 Most Downloaded Versions (by Candidate)
```sql
SELECT version, COUNT(*) as downloads
FROM audit
WHERE candidate = 'gradle'
GROUP BY version
ORDER BY downloads DESC
LIMIT 10;
```
*Uses index:* `idx_audit_candidate_version_timestamp`

### Download Trends by Version Over Time
```sql
SELECT version,
       DATE_TRUNC('week', timestamp) as week,
       COUNT(*) as downloads
FROM audit
WHERE candidate = 'kotlin'
  AND timestamp > NOW() - INTERVAL '6 months'
GROUP BY version, week
ORDER BY week DESC, downloads DESC;
```
*Uses index:* `idx_audit_candidate_version_timestamp`

### Command-Specific Analytics (Install vs Selfupdate)
```sql
SELECT command, COUNT(*) as count
FROM audit
WHERE candidate = 'java'
  AND timestamp > NOW() - INTERVAL '90 days'
GROUP BY command
ORDER BY count DESC;
```
*Uses index:* `idx_audit_candidate_command_timestamp`

### Recent Activity Across All Candidates
```sql
SELECT candidate,
       COUNT(*) as events,
       MAX(timestamp) as last_activity
FROM audit
WHERE timestamp > NOW() - INTERVAL '7 days'
GROUP BY candidate
ORDER BY events DESC;
```
*Uses index:* `idx_audit_timestamp_candidate`

### Client Platform Distribution for a Candidate
```sql
SELECT client_platform,
       COUNT(*) as downloads,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
FROM audit
WHERE candidate = 'kotlin'
  AND timestamp > NOW() - INTERVAL '90 days'
GROUP BY client_platform
ORDER BY downloads DESC;
```
*Uses index:* `idx_audit_candidate_timestamp` (can leverage candidate filter)

### Distribution Popularity Comparison by Candidate Platform (Java Distributions)
```sql
SELECT distribution,
       candidate_platform,
       COUNT(*) as downloads,
       COUNT(DISTINCT DATE_TRUNC('day', timestamp)) as days_active
FROM audit
WHERE candidate = 'java'
  AND candidate_platform = 'LINUX_X64'
  AND distribution IS NOT NULL
  AND timestamp > NOW() - INTERVAL '6 months'
GROUP BY distribution, candidate_platform
ORDER BY downloads DESC;
```
*Uses index:* `idx_audit_candidate_candidate_platform_distribution_timestamp`

### Most Active Users by Host (Top 20)
```sql
SELECT host,
       COUNT(*) as activity_count,
       COUNT(DISTINCT candidate) as unique_candidates,
       MAX(timestamp) as last_seen
FROM audit
WHERE timestamp > NOW() - INTERVAL '30 days'
  AND host IS NOT NULL
GROUP BY host
ORDER BY activity_count DESC
LIMIT 20;
```

### Peak Usage Times (Hourly Distribution)
```sql
SELECT EXTRACT(HOUR FROM timestamp) as hour,
       COUNT(*) as events
FROM audit
WHERE timestamp > NOW() - INTERVAL '7 days'
GROUP BY hour
ORDER BY hour;
```
*Uses index:* `idx_audit_timestamp_candidate`