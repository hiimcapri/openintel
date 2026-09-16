# Project verification

- Run `gradlew.bat build` from `mod/` on Windows (or `./gradlew build` on Unix).
- Run `npm.cmd test` from `relay/` on Windows (`npm test` elsewhere); cut-routing tests execute the real server handlers in a VM with fake sockets and in-memory config, without contacting Discord or changing live users.
- `build` includes the standalone `snitchDimensionTest` and `apiContractTest` Java assertion runners; no JUnit dependency is used. Expected API-test logs include deliberate listener-failure and recursive-event-overflow cases.
- `gradlew.bat publishToMavenLocal` publishes the neutral remapped mod and sources for local Fabric integration development.
- API examples live in `README.md`; a compiling consumer example lives in `mod/src/test/java/example/ExampleOpenIntelIntegration.java`.
- Initialize the integration API on Fabric `ClientLifecycleEvents.CLIENT_STARTED`, not directly in `onInitializeClient`: Minecraft's executor thread identity is not ready during its constructor. The API contract test checks this startup hook.
- Never publish personalized jars or relay credentials as development dependencies. Keep `openintel_token.txt` set to `CHANGE_ME` in source/base builds.
- Read `mod_version` from `mod/gradle.properties` when selecting artifacts; older jars can remain in `mod/build/libs/` after a version bump. The API release is 1.3.0, not 1.2.1.
