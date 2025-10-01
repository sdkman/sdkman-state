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