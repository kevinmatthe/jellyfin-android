# MX Player full-playlist companion

Based on official Jellyfin for Android **v2.7.3** (`9a5f9be6a76da47ca07733b982a12dbd0f75539b`). This branch adds a narrow native handoff for the [Random Playlists server plugin](https://github.com/kevinmatthe/jellyfin-plugin-random-playlist).

The server plugin selects videos; this client resolves all selected Jellyfin item IDs in order using the current user's standard playback API. It then launches MX Player exactly once with an ordered `android.net.Uri[]` `video_list`, matching `video_list.name`, and `video_list_is_explicit=true`. MX owns the queue and next/previous controls. No browser handoff or repeated return to Jellyfin is required by this mode.

## Install and use

Install the companion APK named **Jellyfin MX**, package **org.jellyfin.mobile.randomplaylists**. It installs alongside the official app, with separate login/settings. Log into the same server; in Client Settings choose External Player and select installed MX Player Free or Pro. Open the Random Playlists dialog and generate a queue.

If the server plugin does not detect `ExternalPlayer.supportsPlaylists()`, it offers exports and explains that the companion or a supported MX configuration is required. The old official app is unchanged.

The complete-queue handoff does not fabricate Jellyfin progress or watched state. The normal single-video player keeps its original result handling. Independent per-item subtitle attachments are outside this change; embedded tracks remain in the original videos.

## Bridge

- `supportsPlaylists(): Boolean` is true only for enabled external playback with an installed selected MX Free/Pro package.
- `initPlaylist(json: String)` accepts only `{"ids":["Jellyfin item ID", ...]}` with 1–200 unique canonical or compact UUIDs.
- No arbitrary playback URL is accepted from web content. All sources must resolve before one Intent launches. Account/server/player changes, cancellation or resolution failure abort the pending handoff.
- Native whole-list results are handled separately from a web player's single-item callback, including Activity recreation, to avoid reporting a different video's state.

## Build

Use the upstream pinned toolchain: **JDK 21**, Android SDK **36**, and the checked-in Gradle wrapper. The custom `randomPlaylists` build type keeps the libre flavor and separate package ID, disables app debugging, and uses the local debug signing key for this test distribution.

```sh
./gradlew --no-daemon --max-workers=2 testLibreRandomPlaylistsUnitTest
./gradlew --no-daemon --max-workers=2 -Pjellyfin.version=2.7.3 assembleLibreRandomPlaylists
```

Do not overwrite another install's signing key. This build's signing key must remain stable for future APK updates. Build products and signing files stay outside Git.

The native intent/parser/session tests and APK packaging verification cover the software contract. Real-device validation with the installed MX Player version remains necessary before calling the playback flow verified end to end.

The native regression suite executes 27 tests across six classes. It covers strict parsing and normalized duplicates, all-or-nothing order, failures/cancellation/overlap, one correctly typed Intent, activity ownership/restoration, and real preference notifications including changing the player away and back before resolution completes. A monotonic context generation also invalidates pending work before API authentication/server updates.
