# libinfinivideo

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :video-api:test :video-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

video-api provides typed presentation metadata/descriptors and the existing control time formatter. video-android wraps the existing VideoView/MediaPlayer source binding and reports negotiated surface dimensions/duration without converting frames to bytes. The host retains listeners, Android surface, controls, focus/lifecycle and the staged-file lease. This source tree never used libVLC; no backend was replaced. Decoding remains Android's platform implementation. Standalone video decoding on desktop/iOS and a full demux/frame API are follow-up, not newly implemented capabilities. The actual viewer uses this backend adapter.
