# <img alt="app icon" src=".github/assets/app_icon.svg" width="48" /> mpvKt
A media player for Android based on [mpv-android](https://github.com/mpv-android/mpv-android) aiming to provide a *nicer* user interface over the original.

[![Build](https://github.com/gzlock/mpvKt/actions/workflows/build.yml/badge.svg)](https://github.com/gzlock/mpvKt/actions/workflows/build.yml)

This repository is a derivative work based on the original [mpvKt project](https://github.com/abdallahmehiz/mpvKt) by abdallahmehiz. This fork is maintained at [gzlock/mpvKt](https://github.com/gzlock/mpvKt) and contains modifications. It is distributed under the [Apache License 2.0](LICENSE).
## Additional features
- Nicer player UI
- Better playback history implementation
- Easier customization
- Sleep timer, Speed presets
- Smoother PiP

### v0.1.11
- Natural sorting for file and folder names in the player and SMB browser
- Enhanced SMB directory sorting

### v0.1.10
- Renamed the app to "mpvKt Next"
- Enhanced SMB authentication with progress reporting and MD4/NTLM support
- Improved SMB authentication error handling
- Refactored the SMB profile editing screen with better state management
- Scroll position restoration when browsing SMB directories
- Enhanced ProGuard rules

### v0.1.9
- Refactored PlayerViewModel and removed custom button functionality
- Added project attribution and license information (About screen, NOTICE)

### v0.1.8
- Upgrade mpv-lib from 0.1.9 to 0.1.12

### v0.1.7
- Browse and stream videos from SMB network shares with securely saved connection profiles
- Load external subtitles directly from SMB shares
- Sort and navigate folder playlists in the player, with optional playlist repeat

## Showcase

<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="24%" /> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="24%" /> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="24%"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.png" width="24%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/5_en-US.png" width="49%" /> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/6_en-US.png" width="49%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/7_en-US.png" width="49%" /> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/8_en-US.png" width="49%" />

## Installation
You can download APKs from the [gzlock/mpvKt releases page](https://github.com/gzlock/mpvKt/releases).

You can also access nightly builds from [the fork's Actions workflow](https://github.com/gzlock/mpvKt/actions/workflows/nightlies.yml).

The original project's third-party distribution listings are not official distribution channels for this fork.
## Acknowledgments
- [The original mpvKt project](https://github.com/abdallahmehiz/mpvKt) by abdallahmehiz, on which this fork is based.
- [mpv-android](https://github.com/mpv-android) for the base mpv library to use for this project.

## License
- Project license: [Apache License 2.0](LICENSE)
- Attribution and fork information: [NOTICE](NOTICE)
