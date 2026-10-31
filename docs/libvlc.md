# Android libVLC dependency and source builds

The selected backend is VideoLAN's published Android library:
`org.videolan.android:libvlc-all:3.7.7`. Its exact AAR digest and Stream revision
are in ../dependencies.json. The dependency contains four ABI sets: arm64-v8a,
armeabi-v7a, x86 and x86_64. Only the workspace's ARM64 runtime is exercised here.

Primary upstream locations:

- JNI/build source: https://code.videolan.org/videolan/libvlcjni
- Native engine: https://code.videolan.org/videolan/vlc
- Published Java sources: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7-sources.jar
- Published metadata: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.pom

The POM declares LGPL 2.1; Android source headers specify LGPL 2.1 or later.
The license and attribution ship as assets/third-party/libvlc in this module.
Native codec/contrib notices and the complete source/build recipe still need
release/F-Droid review together with the project's existing native dependencies.
This is not a statement that an original-code license has been selected.

## Replacing the published AAR with an upstream source build

Build the matching libvlcjni release and its pinned VLC/contrib sources using
VideoLAN's Android build instructions/toolchain. Supply that resulting AAR with:

```
gradle -PlibVlcAar=/absolute/path/to/source-built-libvlc.aar :video-android:assembleDebug
```

The same property works in the containing app/composite build. An absolute path
is required so standalone and composite builds select the same file. The adapter
also declares the Android annotation/legacy-support dependencies required by the
AAR. The override avoids a source-tree copy and does not require changing code.
The native VLC toolchain/contrib rebuild was not performed in the Termux
workspace; the normal build resolves the documented published artifact.

## Shared C++ runtime

The app's existing Sound native module and libVLC both ship libc++_shared.so.
Application packaging selects one copy per ABI. In the verified ARM64 app it
retains Sound's existing runtime; the native runner extracts the actual packaged
libraries and loads both libinfiniopenmpt and libvlc/libvlcjni in one process.
Re-run that check whenever the dependency, native toolchain or packaging order
changes. The checked video-only host selects libVLC's runtime instead and passes
the same seven video runtime groups independently. From the containing workspace:

```
python scripts/verify-libvlc-runtime.py
python scripts/verify-libvlc-runtime.py --apk viewer-host/build/outputs/apk/debug/viewer-host-debug.apk --label host
```

Native checks require complete result markers; libVLC redirects standard output
into logcat. The runner records Java assertions separately and rejects an empty
successful process exit.

## Source and output boundaries

The Java binding accepts paths/URIs/file descriptors, not arbitrary Java
ByteSource callbacks. This checkpoint borrows the host's existing local/staged
file, with common source metadata. Direct remote/provider positional reads need
an explicit future transport adapter; credentials and archive URIs are not sent
to libVLC. Sibling subtitle discovery is disabled; embedded subtitles remain
available. Selecting external companion resources requires an explicit resource
contract and is follow-up work.
