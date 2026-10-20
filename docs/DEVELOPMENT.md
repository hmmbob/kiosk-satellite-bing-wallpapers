# Development notes

## Cache behavior

The cache is a rolling copy of the selected feed, not a historical photo archive.

- After successfully reading a valid feed, images no longer in that feed are deleted **before** new image downloads. They are also removed from the slideshow.
- Removed images stay removed even if every replacement download fails.
- An explicit empty `images` array clears the image cache.
- A network error or malformed feed does **not** clear the last valid cached set. It can continue displaying offline.
- A partially downloaded set is saved. Missing images are retried, including after an app restart.
- On startup and reconciliation, obsolete image files and leftover `.tmp` / `.bak` files are removed. Valid recovery backups are restored before cleanup.
- Up to **8 JPEGs**, at most **370,000 bytes each**, are retained. Normal managed storage is under **3 MB**, including the index.
- A hard write limit of **3,395,536 bytes** (about 3.4 MB / 3.24 MiB) includes space for an atomic image write and index replacement. The cache refuses writes beyond that limit.
- Cleanup failures are reported and block additional cache writes, rather than allowing disk use to grow silently.

The initial fetch and periodic refresh can run while the screensaver is inactive. Switching between downloaded images uses no network. Failed refreshes retry after 1, 2, 4, 8, 16 and then up to 30 minutes.

### Persistence compatibility

SDK 1 has no dedicated writable-cache API. The plugin obtains the verified path of its own `assets/cache-anchor.txt` and creates `bing-runtime-cache` alongside the assets directory. It never changes packaged files or adds downloaded images to the verified assets.

This layout is compatible with the inspected KS 2026.10.31 implementation, but is an internal storage dependency rather than a guaranteed SDK storage contract. It may need updating if KS changes its installation layout. Cache normally survives app restarts; plugin updates, reinstallations or removal can clear it.

If the persistent directory cannot be created, the plugin reports that photos could not be saved and internet will be needed after a restart. Offline display then lasts only until the plugin or app restarts. If an existing cache cannot be cleaned, its error is surfaced and new disk writes are blocked. Technical causes go to diagnostic logs rather than the user status.

## Rendering and network limits

The Java plugin fetches the archive and images from `https://www.bing.com`. Only that HTTPS host is allowed, including redirects. No HA credentials, tracking or analytics are used.

The renderer is a self-contained HTML document with one embedded JPEG. It makes no network requests and stays within KS's 512 KiB inline-document limit. There is no crossfade or animated zoom.

At most eight compressed images are held for the slideshow, plus temporary download and rendering buffers. The plugin does not decode all of them into bitmaps at once.

Bing's archive endpoint is not a guaranteed public API. Availability, regional behavior and resolution variants can change. The live endpoint returned at most eight images even when more were requested. Some regions, including nl-NL, may use international images or English descriptions.

The ZIP contains no Bing photographs. Downloaded photographs remain subject to their owners' rights and are not covered by the source-code license. This plugin is not affiliated with Microsoft or Bing.

## Build and test

Use Python 3, JDK 17 or newer, and Android SDK platform/build-tools 35. Set `JAVA_HOME` and `ANDROID_HOME`.

```text
python tools/test.py
python tools/build.py --android-platform 35
```

Tests cover parsing, image limits, escaping, cache recovery, repeated and partial feed changes, empty/malformed feeds, disk-write limits, cleanup failure, offline restart, timers, settings races, publication pacing, English messages and choices with stable stored values.

Edit settings descriptions in `kiosk-satellite-plugin.json`. Runtime messages and choice labels live in `assets/texts.json`; after editing them, run `python tools/texts.py --write`. The test and build tools check that the generated Java matches. There is one English package: KS currently has no plugin localization API.

An optional live smoke test downloads and decodes the current feed:

```text
python tools/test.py /path/to/temporary-smoke-output
```

Its downloaded photographs and HTML pages are test output and must not be included in a release.

The build produces the plugin ZIP, a matching manifest and SHA-256 checksum under `dist/`. SDK classes are compile-time dependencies and are not bundled in the plugin's DEX.

## Publishing

The repository uses the [official KS release workflow](https://github.com/jxlarrea/kiosk-satellite-plugins/blob/2260f801f047dab06a1ca0c1c4f7bec926cb73b1/.github/workflows/build.yml), with the checkout and Java setup Actions updated to supported versions.

1. Update the source manifest version and changelog, run the tests, and commit the source.
2. Tag that commit, for example `v0.4.0`, and publish a stable GitHub release for that tag.
3. Let the **Release** workflow test and build the tagged source. It uploads `kiosk-satellite-plugin.json`, `<id>-<version>.zip` and `<id>-<version>.zip.sha256`.
4. Verify the workflow succeeded and all three assets were uploaded by `github-actions[bot]`. Do not upload them manually or with a personal access token: KS rejects those uploads.

The release tag supplies the package version. The standalone and packaged manifests must be identical. For an interrupted build, retry the workflow on the existing release tag. Publish a new version for code or package changes.

See the [KS publication requirements](https://github.com/jxlarrea/kiosk-satellite-plugins/blob/2260f801f047dab06a1ca0c1c4f7bec926cb73b1/docs/creating-plugins.md#repository-and-release). A public repository release does not submit the plugin to the KS community database.

## License and attribution

Apache-2.0. See `LICENSE` and `assets/NOTICE.txt` for upstream notices and pinned revisions.

The official KS SDK and build helpers are included in the source distribution. The small JSON reader and Base64 implementation are adapted from Coffmad's Google Photos screensaver. The existing ndesgranges/bing-wallpaper integration was consulted for market/resolution behavior; its Python code is not included.
