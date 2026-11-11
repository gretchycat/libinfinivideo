# libinfinivideo

## Reproducible standalone build

Install JDK 17 or 21, Python 3, Android SDK platform 36/build tools 35.0.0,
and (for JNI modules) NDK 27.2.12479018/CMake 3.22.1. Set `ANDROID_HOME` or
this checkout's untracked `local.properties`. The checked-in Gradle 9.7.1 wrapper
is verified by its distribution SHA-256.

```sh
python3 scripts/fetch-dependencies.py
./gradlew :video-api:test :video-android:testDebugUnitTest :video-android:assembleDebug
```

The lock includes only this library's required source dependencies, including
transitive dependencies. Fetching checks pin agreement and refuses to overwrite
local changes. `python3 scripts/fetch-dependencies.py --check` checks revisions.
No application checkout is required. CI uses the same commands from a fresh clone.


Runtime discovery: [handler schema, registration, linked dependencies and limits](CAPABILITIES.md).

Reusable video playback contracts and platform backends using libinfinistream.
The Android app now selects **libVLC**, at the owner's request. The library owns
its native player, video/subtitle surfaces, playback events and audio focus.
Consuming applications own controls, navigation, lifecycle decisions and source leases.

## Build

Use JDK 17+ and Gradle 9.7.1; Android requires SDK 36/min API 26.
Run the dependency bootstrap above to fetch the pinned stream library.

```
gradle :video-api:test :video-android:assembleDebug :video-android:testDebugUnitTest
```

The Android backend pins `org.videolan.android:libvlc-all:3.7.7` from Maven Central.
No AAR or native binary is committed here. See [upstream/source-build details](docs/libvlc.md).
Original-code licensing remains pending owner selection; see LICENSE.md.

## API and ownership

`video-api` contains the backend-neutral `VideoPlayback` controls/events,
`VideoScaleMode`, long millisecond positions, metadata and descriptors. No
Android or libVLC types appear in these contracts. Output describes the actual
presentation surface, dimensions/duration when known, backend and negotiated
seekability. It does not advertise CPU frame bytes or positional frame access.

`video-android` provides `VideoCapabilities.create(context, metadata)`, which
selects an installed `VideoCapabilityHandler` and returns `VideoSurfacePlayback`.
Add its `view` and call `openFile(file, metadata, autoPlay)` or
`openResource(resource, autoPlay)` directly. Declare the Android module dependency;
discovery checks class/native availability rather than assuming libVLC exists.

The explicit `LibVlcVideoBackend(context)` API remains compatible. Add its `view` to the host
layout and call `open(file, sourceMetadata, autoPlay)` for local files or
`open(resource, autoPlay)` for common sources. All presentation/control
calls are on the Android main thread. The file is a borrowed local view of a
common source, including a cached remote/archive resource. Input capabilities and
length describe that actual file; provenance/name/warnings retain logical source
identity. Stop/close the backend **before** closing the host's staging lease.
The backend does not delete or copy its input file.

Common resources use `VideoStreamServer`, a library-owned loopback HTTP adapter
for native libVLC. It opens source handles only when requested, serves 64 KiB
chunks, and uses actual positional reads for byte ranges. Reopenable sequential
resources with a known total can seek by replaying a prefix without a disk copy;
that cost is explicit in input metadata. Unknown totals remain sequential and
use chunked HTTP. A selector handles socket backpressure, with two source workers
and at most eight connections; paused responses cannot occupy source workers.
The URL binds only to loopback and uses a per-session random token. Stop closes
sockets/borrowed handles before the caller closes its resource owner. Logical
identity, type, warnings and actual source capabilities remain metadata.
Local/network startup buffers are 300 ms, overriding the binding's hardware
setup default of 1500 ms. The host starts the selected item while it discovers
folder siblings independently.

The adapter supports fit, aspect-preserving fill, 16:9/4:3, playback speed,
play/pause, indexed seeking, embedded subtitle presentation, audio focus and
explicit stop/close. Each open uses a fresh native player and rejects stale
callbacks. Settings persist across opens. `stop` releases the current native
player; `close` also releases libVLC and is idempotent. The host chooses playlist,
repeat, PiP and background policy. Rendering uses native surfaces without routing
frames through byte arrays.

`AndroidVideoBackend` and prior metadata/time APIs remain compatibility adapters;
the current host uses libVLC. Existing clients can migrate incrementally. Desktop
and iOS adapters and a general demux/frame API remain future work. The app now
uses common positional or sequential resources for remote/archive inputs.
Vault's non-extracting slice API avoids its legacy whole-entry fallback; stored
ZIP/filesystem slices remain positional and compressed ZIP streams can replay.
Other inherited container adapters may still cache enclosing resources or
require extraction; their format-specific limits remain explicit follow-up.

## Validation

The containing workspace records the frozen regression baseline and native
runtime checks in docs/infinistream-migration.md. Portable tests cover metadata,
source adaptation, speed/seek limits and long-duration labels. The native helper
loads libVLC and Sound against the exact packaged shared C++ runtime, probes the
synthetic MP4/WebM/MKV fixtures and checks decoder counters and long seeks
without creating widgets. The streaming mode uses a dummy audio clock, disables
video rendering, and tests actual MP4 demux/seek/decoder progress over source
ranges. It also checks a 512 MiB virtual movie without whole-file reads, compressed
ZIP replay, unknown-length inputs and native container slices. Actual surfaces, sound, subtitles, PiP and remote input remain
handset acceptance checks.
