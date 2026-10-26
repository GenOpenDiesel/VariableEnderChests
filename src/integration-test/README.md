# Disposable Paper integration test

Never install this helper on a real server. It generates synthetic `playerdata`
for UUIDs `00000000-0000-0000-0000-000000000001` through `...0007` and resets
those fixtures. It refuses to run unless the main world is called `test-world`,
the working directory contains `ALLOW_SYNTHETIC_VEC_TESTS`, and nobody is online.

Build the main plugin with `mvn package`. Compile `java/MoveIntegrationTest.java`
against that **shaded JAR** and the disposable server's Paper API/runtime libraries.
Package the resulting classes, the files in `resources/`, and this directory's `plugin.yml` into
`MoveIntegrationTest.jar`. Install both JARs in the disposable server's `plugins/`.

Bind the test server to `127.0.0.1`, use `server-port=0`, and start it without players.
The helper runs automatically, prints `INTEGRATION PASS` or `INTEGRATION FAILED`,
and shuts the server down. The deliberate database rejection and invalid item
tests print expected conversion error stack traces before a successful test result.

Covers full/sparse/new chests, all 81 stacks, custom metadata and shulker contents,
source removal, preservation of unrelated NBT, repeated moves, recovery after DB
commit, transaction rollback, malformed source safety, retrieval across repeated
opens and row changes, shared block/command/native inventory routing, and migration
login/API/command locks. The synthetic 1.21.11 fixtures also check old database
records, vanilla player data, and nested shulker contents after a server upgrade.
On 26.3 the full-chest test includes a new 26.3 item.

The original migration suite was tested against Paper 1.21.11 with Java 21.
Minecraft 26.3 requires Java 25; use that runtime for the 26.3 compatibility suite.
Paper 26.3 build 223 with Java 25 passed all 119 assertions on 2026-10-26.
All bundled fixtures contain only synthetic test inventory data.
