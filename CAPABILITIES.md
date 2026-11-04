# Runtime capabilities — video

Updated 2026-11-04.

## Linked dependencies

Stream through video-api/video-android; libVLC is an explicit video-android implementation dependency, with the existing optional local-AAR substitution.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve sibling repositories through includeBuild (or
`-PinfiniLibrariesDir=/path/to/libraries`). Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`VideoCapabilities.registry`, `VideoCapabilityHandler`, `VideoSurfacePlayback`; the viewer discovers a backend and calls its library API directly. Existing LibVlcVideoBackend open methods remain compatible.

Use `RuntimeCapabilities.library("video")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

Register a playback handler/provider supplying a real VideoSurfacePlayback. Its format facts, backend availability and restrictions belong to the handler, not to an application format switch.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. Use existing Stream sources, positional I/O,
owned outputs and typed processors; do not duplicate decoders in consumers.

## Limits

libVLC class/native presence is checked at runtime; codecs, demux support, Android surface setup and source seeking are conditional until open. This change does not implement a new decoder or claim CPU frame-pipeline support.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
gradle :video-api:test :video-android:testDebugUnitTest :video-android:assembleDebug --offline
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
