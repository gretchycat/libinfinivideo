# libinfinivideo

Reusable video playback contracts and platform backends using libinfinistream.
The Android app now selects **libVLC**, at the owner's request. The library owns
its native player, video/subtitle surfaces, playback events and audio focus.
InfiniFolder owns controls, navigation, lifecycle decisions and source leases.

## Build

Use JDK 17+ and Gradle 9.7.1; Android requires SDK 36/min API 26.
Clone sibling libinfinistream at the revision pinned by the containing workspace,
or set `-PinfiniLibrariesDir=/path/to/libraries`.

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

`video-android` provides `LibVlcVideoBackend(context)`. Add its `view` to the host
layout and call `open(file, sourceMetadata, autoPlay)`. All presentation/control
calls are on the Android main thread. The file is a borrowed local view of a
common source, including a cached remote/archive resource. Input capabilities and
length describe that actual file; provenance/name/warnings retain logical source
identity. Stop/close the backend **before** closing the host's staging lease.
The backend does not delete or copy its input file.

The adapter supports fit, aspect-preserving fill, 16:9/4:3, playback speed,
play/pause, indexed seeking, embedded subtitle presentation, audio focus and
explicit stop/close. Each open uses a fresh native player and rejects stale
callbacks. Settings persist across opens. `stop` releases the current native
player; `close` also releases libVLC and is idempotent. The host chooses playlist,
repeat, PiP and background policy. Rendering uses native surfaces without routing
frames through byte arrays.

`AndroidVideoBackend` and prior metadata/time APIs remain compatibility adapters;
the current host uses libVLC. Existing clients can migrate incrementally. Desktop
and iOS adapters, a general demux/frame API and direct arbitrary Stream read
callbacks remain future work. Remote/archive inputs currently keep explicit
host staging; this checkpoint does not claim on-demand network ranges.

## Validation

The containing workspace records the frozen regression baseline and native
runtime checks in docs/infinistream-migration.md. Portable tests cover metadata,
source adaptation, speed/seek limits and long-duration labels. The native helper
loads libVLC and Sound against the exact packaged shared C++ runtime, probes the
synthetic MP4/WebM/MKV fixtures and checks software decode and long seeks without
creating widgets. Actual surfaces, sound, subtitles, PiP and remote input remain
handset acceptance checks.
