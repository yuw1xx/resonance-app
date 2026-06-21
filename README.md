> [!IMPORTANT]
> Please join my Discord server, I'm currently looking for testers so I can release the app on Google Play as well, thank you!! https://discord.gg/SftqvvveMj

<div align="center">
  <h1>Resonance</h1>
  <p>A modern, feature-rich local music player for <b>Android 15 and newer</b></p>
  <p>Inspired by <a href="https://github.com/namidaco/namida">Namida</a>, <a href="https://github.com/theovilardo/PixelPlayer">Pixel Player</a> & <a href="https://github.com/FoedusProgramme/Gramophone">Gramphone</a> 💜</p>

  ![Android](https://img.shields.io/badge/Android-API%2035+-3DDC84?logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
  ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)
  ![License](https://img.shields.io/badge/License-MIT-blue)
</div>

---

## Overview

Resonance is a powerful local music player built entirely with Kotlin and Jetpack Compose. Designed for audiophiles who want full control over their listening experience — from ReplayGain and crossfade to synced lyrics, Last.fm scrobbling, Navidrome streaming, and peer-to-peer song sharing. No ads, no subscriptions, completely FOSS forever.

## Screenshots

| Home | Player | Settings | Artist |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/Screenshot_20260407-001502.png" width="200"> | <img src="screenshots/Screenshot_20260407-001514.png" width="200"> | <img src="screenshots/Screenshot_20260407-001534.png" width="200"> | <img src="screenshots/Screenshot_20260407-001546.png" width="200"> |

| Playlist | Resonance Share | Search | Lyrics |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/Screenshot_20260407-230040.png" width="200"> | <img src="screenshots/Screenshot_20260407-230052.png" width="200"> | <img src="screenshots/Screenshot_20260407-230127.png" width="200"> | <img src="screenshots/Screenshot_20260407-230239.png" width="200"> |

## Features

### Playback
- [x] **Gapless playback** — seamless transitions using ExoPlayer's native gapless support
- [x] **Crossfade** — configurable crossfade duration between songs
- [x] **ReplayGain 2.0** — per-track and per-album volume normalization with configurable preamp
- [x] **Skip silence** — automatically skip silent passages
- [x] **Playback speed & pitch control** — independent speed and pitch adjustment
- [x] **Smart shuffle** — history-aware shuffle that avoids repeating recently played tracks
- [x] **Sleep timer** — stop after a set number of tracks or minutes

### Library
- [x] **Full MediaStore integration** — automatically detects all local audio files
- [x] **Configurable artist delimiters** — split multi-artist tags the way you want
- [x] **Folder browsing** — navigate your music by directory
- [x] **Excluded folders** — hide folders from the library
- [x] **Auto-scan** — scheduled background library refresh
- [x] **Sort & filter** — sort by title, artist, album, date added, duration, play count, and more
- [x] **Persistent queue** — queue survives app restarts

### Streaming
- [x] **Navidrome / Subsonic** — full Subsonic API support: browse, play, and scrobble from your self-hosted server
- [x] **Chromecast** — cast to any Google Cast device directly from the player

### Lyrics
- [x] **Synced lyrics** — LRC, TTML, and word-level karaoke support
- [x] **Embedded lyrics** — reads lyrics from ID3 tags via JAudioTagger
- [x] **LRCLib integration** — automatic online lyrics fetching
- [x] **Lyrics editor** — edit and save lyrics directly in the app

### Metadata & Artwork
- [x] **Tag editor** — edit title, artist, album, genre, year, track number, and more
- [x] **Artwork fetching** — automatic album art from MediaStore and online sources
- [x] **Artist images** — artist photos fetched from the Deezer API
- [x] **Waveform seekbar** — real-time waveform visualization extracted from the audio file

### Scrobbling
- [x] **Last.fm** — automatic scrobbling, now playing updates, and loved track sync
- [x] **Maloja** — self-hosted scrobbling via the Maloja API

### Mixes
- [x] **Auto-generated playlists** — weekly mixes built from your listening history (top artist, top genre)
- [x] **Navidrome mixes** — mix generation works with your Navidrome library too

### Statistics
- [x] **Listening history** — track your most played songs and artists over time
- [x] **Play breakdowns** — see when you listen most, by hour and time period

### Sharing
- [x] **Resonance Share** — send songs directly to nearby devices over Wi-Fi via Google Nearby Connections

### Backup
- [x] **Playlist & liked song backup** — export and import as JSON

### Equalizer
- [x] **System equalizer** — access and control EQ presets from within the app

### UI & Customization
- [x] **Material 3** — full Material You dynamic color support
- [x] **Preset colors** — choose from a set of curated accent colors
- [x] **Dark / Light / System theme** — follows system or manual override
- [x] **Blur artwork background** — blurred album art as the player background
- [x] **Artwork animation** — rotating vinyl / artwork animation while playing
- [x] **Configurable corner radius** — round or sharp UI corners
- [x] **Mini player styles** — choose your preferred mini player layout
- [x] **Player layouts** — multiple full-screen player designs
- [x] **Haptic feedback** — tactile response on controls

### Home Screen Widget
- [x] **Now-playing widget** — displays the current song title and artist on your home screen
- [x] **Playback controls** — play/pause and skip directly from the widget
- [x] **Live updates** — widget stays in sync with the player in real time

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository pattern |
| DI | Hilt |
| Media | Media3 / ExoPlayer + MediaSession |
| Widget | Jetpack Glance |
| Database | Room |
| Networking | Retrofit 2 + OkHttp + Moshi |
| Image loading | Coil |
| Preferences | DataStore |
| Background work | WorkManager |
| Tag reading | JAudioTagger |
| Casting | Google Cast SDK |
| P2P sharing | Google Nearby Connections |

## Getting Started

### Prerequisites

- Android Studio Hedgehog or newer
- Android SDK 35
- JDK 17

### Build

```bash
git clone https://github.com/yuw1xx/resonance-app.git
cd resonance-app
./gradlew assembleDebug
```

## Architecture

```
resonance/
├── cast/               # Google Cast (CastManager, LocalAudioServer)
├── data/
│   ├── database/       # Room entities, DAOs, migrations
│   ├── model/          # Domain models (Song, Album, Artist, Playlist…)
│   ├── network/        # Retrofit APIs (Last.fm, Maloja, Navidrome, LRCLib)
│   ├── preferences/    # DataStore preferences
│   ├── repository/     # Data repositories
│   ├── service/        # MusicService, NearbyShareManager, BackupManager, EQ
│   └── worker/         # AutoScanWorker, MixGeneratorWorker
├── di/                 # Hilt modules
├── domain/
│   └── usecase/        # WaveformExtractor, ReplayGainProcessor, TagEditor, MediaSyncWorker
├── presentation/
│   ├── navigation/     # Compose NavGraph
│   ├── screens/        # Player, Library, Settings, Lyrics, Folders, Statistics, Setup, Share
│   ├── viewmodel/      # PlayerViewModel, LibraryViewModel, SettingsViewModel, and more
│   └── components/     # Shared Compose components
└── ui/
    ├── glancewidget/   # Home screen widget
    └── theme/          # Material 3 theme, typography, colors
```

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'Add my feature'`)
4. Push the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.
