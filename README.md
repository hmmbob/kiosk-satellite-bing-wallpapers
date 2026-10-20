# Bing Wallpapers for Kiosk Satellite

![Bing icon](assets/icon.png)

Show recent Bing photos as a slideshow on your kiosk. Choose how many photos to show and how long each stays on screen. Saved photos remain available when your internet connection drops. Old photos are removed automatically.

## Install

Requires Android 7 or newer and [Kiosk Satellite](https://kiosksatellite.com/) with plugin support. Compatibility was checked against KS 2026.10.31.

1. Open **Plugin Manager** in Kiosk Satellite and choose **Install from a GitHub repository**.
2. Enter `https://github.com/hmmbob/kiosk-satellite-bing-wallpapers`.
3. Review the plugin details, install it and enable **Bing Wallpapers**.
4. Wait until the plugin reports that photos are available.
5. Under **Screensaver**, choose **Slideshow (Bing Wallpapers)**.

If the option is missing in the web interface, refresh the page once. KS 2026.10.31 does not update this list when a plugin adds a screensaver.

An internet connection is needed for the first download and after a plugin update. The plugin's settings and messages are in English. The Bing region controls the photo selection and can affect caption language.

## Make it yours

- **Number of photos:** choose 1 to 8 recent photos. Bing may provide fewer.
- **Time per photo:** choose how long each photo stays on screen.
- **Bing region:** choose a region for the photos and captions. Different regions may show the same photos. Captions may be in English.
- **Fit to screen:** fill the screen or show the whole photo.
- **Show caption and photo credit:** show Bing's description and credit at the bottom of the screen.

KS continues to control when the screensaver starts and stops. To stop using this plugin, choose another screensaver and disable **Bing Wallpapers**.

## Updates

To update, open the installed plugin's repository entry in Plugin Manager, review the latest release and install the update.

## Install from ZIP

For local testing, download the plugin ZIP from [Releases](https://github.com/hmmbob/kiosk-satellite-bing-wallpapers/releases). In KS, open **Plugin Manager → Developer Tools → Install from ZIP**, select the ZIP without extracting it, and then enable the plugin.

If you installed the plugin from GitHub, you must uninstall it before installing a ZIP. Uninstalling deletes the plugin's settings.

## Development and credits

See [developer notes](docs/DEVELOPMENT.md) for building, testing and cache behavior.

The source code uses [Apache 2.0](LICENSE). The Bing icon comes from [Home Assistant Brands](https://github.com/home-assistant/brands/tree/70f4b4c618d2966e40e8083e693a42794c11d3a8/custom_integrations/bing_wallpaper); it is a Microsoft brand asset and is not covered by this project's Apache license. Bing photos retain their own copyright. This is an independent community plugin, not a Microsoft product. See [credits](assets/NOTICE.txt).
