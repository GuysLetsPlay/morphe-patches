# Open HLS streams in mpvRx

This Morphe patch adds a stream picker to Free Adblocker Browser. On supported AniWorld and video-host pages, it detects HLS playlists, displays resolution and bitrate when the provider exposes them, and opens the selected playlist in MPV RX. When the AniWorld episode or hoster page exposes its episode identity, the handoff also supplies a readable title plus anime title, season, and episode hints for mpvRx's online intro/outro lookup.

## Apply the patch

1. Install MPV RX on the Android device.
2. In Morphe Manager, select a **universal APK** for Free Adblocker Browser **139.1.4146** (`com.hsv.freeadblockerbrowser`, version code `2016124146`).
3. Select **Open HLS streams in mpvRx** and build/install the patched app.
4. Open a supported streaming page and start playback. The floating button remains visible while waiting for an HLS stream, then changes to **Open ... in mpvRx** when a playlist is detected. The adjacent menu lists detected quality variants and link-copy actions.

The patch is pinned to the exact browser version tested. It edits the browser's existing encrypted video-script and host-list assets, so a later browser version needs its assets and host behavior checked before the target is expanded.

If the provider exposes an HLS master playlist, the button shows a label such as `Open 720p | 1.1 Mbps in mpvRx`. If the provider prevents playlist inspection, the helper labels quality as unknown and opens the provider's Auto playlist rather than guessing.

For VidMoly pages whose document title contains a release label such as `Classroom of the Elite S01E01 ...`, the helper passes `Classroom of the Elite - S01E01` as the playback title and passes the series title, season, and episode as mpvRx intent extras. AniWorld season/episode page URLs are also recognized when the browser exposes them as the referring URL. HLS URLs named `master.m3u8` or `index.m3u8` therefore do not have to supply the title themselves.

## Technical approach

The app already injects its built-in video-page JavaScript on configured hostnames. The patch extends that script and host list; it does not replace app code or ship vendor APK files. The encrypted assets use DES/ECB/PKCS5Padding around a raw DEFLATE stream, with the app's embedded `spring.s` key.
