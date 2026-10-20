# Changelog

## 1.0.1

- Explain network access in the installation README for Community Plugins review.
- No changes to plugin behavior or saved settings.

## 1.0.0

- Promote the plugin to 1.0.0 after a successful kiosk test of 0.4.0.
- Keep the slideshow behavior, plugin ID and saved settings unchanged.
- Include the updated documentation and contribution guidelines.

## 0.4.0

- First public release, with one English installation package.
- Use the blue Bing icon from Home Assistant Brands, with source attribution.
- Publish verified installation files through the official KS GitHub Actions workflow.
- Retain slideshow settings, offline photos and automatic removal of photos no longer in the feed.

## 0.3.1-test

- Use Tabler Icons brand-bing with the MIT license and attribution bundled.

- Change plugin ID to `kiosk-satellite-bing-wallpapers` and update its screensaver mode identifier. KS treats this as a new installation; old plugin settings do not transfer automatically.
- Language builds remain a temporary test workaround, pending a supported single-package localization solution.

## 0.3.0-test

- Dutch and English settings, choices, actions and screensaver messages, built from translation catalogues.
- Simpler user-facing descriptions and messages; technical causes stay in logs.
- A 256px landscape icon with Apache-2.0 attribution.
- Existing saved choices retained when upgrading or switching language builds.
- Documented KS Remote Admin list-refresh defect; proposed host fix supplied separately.

## 0.2.0-test

- Use the mode title Slideshow so KS displays Slideshow (Bing Wallpapers), without a repeated name. Keep the existing plugin ID and mode key.
- Generalize settings and documentation for Android kiosks, without device-specific recommendations.
- Reconcile the image cache with each successfully fetched feed before downloading replacements. Remove obsolete images even when replacements fail.
- Clear cached images on a valid empty feed; preserve them on network failures or malformed feeds.
- Bound the cache to eight images and a 3,395,536-byte write ceiling, including temporary replacement space.
- Clean up interrupted writes and report cleanup failures instead of silently accumulating files.
- Pace screen publications and coalesce bursts, retrying the latest requested page after temporary rejection.
- Extend tests for cache churn, partial downloads, storage limits, failed cleanup and fast user actions.

## 0.1.0-local

- Initial locally tested screensaver: recent Bing images, configurable timing, fitting, region and offline cache.
