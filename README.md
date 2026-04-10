# ReClip

A self-hosted, open-source video and audio downloader with a clean web UI. Paste links from YouTube, TikTok, Instagram, Twitter/X, and 1000+ other sites — download as MP4 or MP3.

![Python](https://img.shields.io/badge/python-3.8+-blue)
![License](https://img.shields.io/badge/license-MIT-green)

https://github.com/user-attachments/assets/419d3e50-c933-444b-8cab-a9724986ba05

![ReClip MP3 Mode](assets/preview-mp3.png)

## Features

- Download videos from 1000+ supported sites (via [yt-dlp](https://github.com/yt-dlp/yt-dlp))
- MP4 video or MP3 audio extraction
- Quality/resolution picker
- Bulk downloads — paste multiple URLs at once
- Automatic URL deduplication
- Clean, responsive UI — no frameworks, no build step
- Single Python file backend (~150 lines)

## Quick Start

```bash
brew install yt-dlp ffmpeg    # or apt install ffmpeg && pip install yt-dlp
git clone https://github.com/averygan/reclip.git
cd reclip
./reclip.sh
```

Open **http://localhost:8899**.

## Android App

This repo now also includes a native Android client in [`android/`](./android) that runs `yt-dlp` directly on-device.

- Video downloads are saved into **Movies/ReClip** so Android gallery apps can index them.
- MP3 downloads are saved into **Music/ReClip**.
- The app keeps using `yt-dlp` on-device instead of a site whitelist, and it performs a background check for the latest stable `yt-dlp` release so support for sites like YouTube, TikTok, and BBC iPlayer stays closer to the desktop/web version.

### Build the APK

1. Install the Android SDK command-line tools or Android Studio.
2. Make sure `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at your SDK.
3. Build from the Android project folder:

```bash
cd android
./gradlew :app:assembleDebug
```

### APK Output

After a successful build, debug APKs are created in:

```bash
android/app/build/outputs/apk/debug/
```

For most modern Android phones, the smallest useful file is:

```bash
android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

If you want one APK that works across more device types, use:

```bash
android/app/build/outputs/apk/debug/app-universal-debug.apk
```

### Android Notes

- The Android client supports the same core flow as the web app: paste URLs, fetch info, choose MP4 or MP3, then download on-device.
- On Android 11 and later, you will be prompted to allow file access so ReClip can save media into the public Movies and Music folders.
- The Android app is aimed at direct installs / sideloading rather than Play Store release hardening.

Or with Docker:

```bash
docker build -t reclip . && docker run -p 8899:8899 reclip
```

## Usage

1. Paste one or more video URLs into the input box
2. Choose **MP4** (video) or **MP3** (audio)
3. Click **Fetch** to load video info and thumbnails
4. Select quality/resolution if available
5. Click **Download** on individual videos, or **Download All**

## Supported Sites

Anything [yt-dlp supports](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), including:

YouTube, TikTok, Instagram, Twitter/X, Reddit, Facebook, Vimeo, Twitch, Dailymotion, SoundCloud, Loom, Streamable, Pinterest, Tumblr, Threads, LinkedIn, and many more.

## Stack

- **Backend:** Python + Flask (~150 lines)
- **Frontend:** Vanilla HTML/CSS/JS (single file, no build step)
- **Download engine:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) + [ffmpeg](https://ffmpeg.org/)
- **Dependencies:** 2 (Flask, yt-dlp)

## Disclaimer

This tool is intended for personal use only. Please respect copyright laws and the terms of service of the platforms you download from. The developers are not responsible for any misuse of this tool.

## License

[MIT](LICENSE)
