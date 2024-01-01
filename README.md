# SDKMAN State API

This application exposes the SDKMAN Candidate and Version state through a JSON API.

It exposes `GET`, `POST`, `PATCH` and `DELETE` HTTP methods on candidates and versions.

The audience of this API is twofold:

* as a backend for the [native components](https://github.com/sdkman/sdkman-cli-native) written in Rust
* as admin API of the datastore, used directly or by build system plugins and
  the [DISCO integration](https://github.com/sdkman/sdkman-disco-integration)