# Source provenance

Original implementations and history: [gretchycat/infinifolder at 9233495](https://github.com/gretchycat/infinifolder/tree/9233495). Existing codecs/renderers are moved without rewriting algorithms; package names and notices are retained for compatibility. VideoView input binding and time-formatting were extracted from VideoPlayerActivity; platform surface, controls, listeners and leases remain host-owned. New adapters describe Stream inputs/actual outputs. Original-code license selection is pending (LICENSE.md).

| Original path | New path | SHA-256 before move |
| --- | --- | --- |

## 2026-10-31 — requested libVLC backend

The owner explicitly requested libVLC in place of Android platform video decoding.
The new playback contract, metadata adapters and Android presentation backend are
original integration code. VideoLAN's pinned Maven dependency supplies libVLC;
its sources, digest, notices and optional source-built AAR override are recorded
in dependencies.json and docs/libvlc.md. No upstream codec code or binary is
copied into this repository. The original platform adapter remains for existing
clients while the host selects the new backend.
