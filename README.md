# Bing Wallpapers for Kiosk Satellite

![Bing icon](assets/icon.png)

Show recent Bing photos as a screensaver slideshow on your kiosk. Choose how many photos to show and how long each stays on screen. Saved photos remain available when your internet connection drops. Old photos are removed automatically.

## Installation

Requires Android 7 or newer and [Kiosk Satellite](https://kiosksatellite.com/) with plugin support. Compatibility was checked against KS 2026.10.31.

### Community Plugins (Recommended)

Bing Wallpapers is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=hmmbob/kiosk-satellite-bing-wallpapers)

Or search for `Bing Wallpapers` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/hmmbob/kiosk-satellite-bing-wallpapers` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

### Start the screensaver

1. Enable **Bing Wallpapers** in Plugin Manager.
2. Wait until the plugin reports that photos are available.
3. Under **Screensaver**, choose **Slideshow (Bing Wallpapers)**.

KS 2026.10.33 and newer update the screensaver list automatically. On older versions, refresh the web page once if the option is missing.

An internet connection is needed for the first download and after a plugin update. The plugin's settings and messages are in English. The Bing region controls the photo selection and can affect caption language.

## Make it yours

- **Number of photos:** choose 1 to 8 recent photos. Bing may provide fewer.
- **Time per photo:** choose how long each photo stays on screen.
- **Bing region:** choose a region for the photos and captions. Different regions may show the same photos. Captions may be in English.
- **Fit to screen:** fill the screen or show the whole photo.
- **Show caption and photo credit:** show Bing's description and credit at the bottom of the screen.

KS continues to control when the screensaver starts and stops. To stop using this plugin, choose another screensaver and disable **Bing Wallpapers**.

## Network access

The plugin downloads the photo list and images directly from `https://www.bing.com`. Requests include the selected Bing region and photo count. The plugin does not send Home Assistant data or collect analytics.

## Saved photos

Photos are saved in `bing-runtime-cache` inside the plugin's installed package directory, alongside `assets/`. KS does not yet provide a dedicated cache API, so this location depends on its current installation layout. Updates or uninstalling can clear the saved photos. If the plugin cannot create this folder, it keeps photos in memory; an internet connection is then needed again after a restart.

Photos no longer in the latest successfully retrieved feed are removed automatically. If Bing cannot be reached, the last saved photos remain available.

## Updates

To update, open the installed plugin's repository entry in Plugin Manager, review the latest release and install the update.

## Install from ZIP

For local testing, download the plugin ZIP from [Releases](https://github.com/hmmbob/kiosk-satellite-bing-wallpapers/releases). In KS, open **Plugin Manager → Developer Tools → Install from ZIP**, select the ZIP without extracting it, and then enable the plugin.

If you installed the plugin from GitHub, you must uninstall it before installing a ZIP. Uninstalling deletes the plugin's settings.

## Development and credits

See [developer notes](docs/DEVELOPMENT.md) for building, testing and cache behavior.

Bug reports and focused pull requests are welcome. Please read [Contributing](CONTRIBUTING.md) before proposing a change.

The source code uses [Apache 2.0](LICENSE). The Bing icon comes from [Home Assistant Brands](https://github.com/home-assistant/brands/tree/70f4b4c618d2966e40e8083e693a42794c11d3a8/custom_integrations/bing_wallpaper); it is a Microsoft brand asset and is not covered by this project's Apache license. Bing photos retain their own copyright. This is an independent community plugin, not a Microsoft product. See [credits](assets/NOTICE.txt).
