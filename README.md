<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/screenshots/title_dark.png">
    <img src="./docs/screenshots/title_light.png" width="400" alt="Fision">
  </picture>
  <p><b>The magic starts with the fision of your music.</b></p>
  <p>
    <img src="https://img.shields.io/badge/Java-ED8B00?logo=openjdk&logoColor=white" alt="Java">
    <img src="https://img.shields.io/badge/C%2B%2B-00599C?logo=cplusplus&logoColor=white" alt="C++">
    <img src="https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android&logoColor=white" alt="Android 8.0+">
    <a href="https://github.com/jotapemj/Fision-Server"><img src="https://img.shields.io/badge/Server-Python-3776AB?logo=python&logoColor=white" alt="Server: Python"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-BD0000" alt="License: GPL-3.0"></a>
  </p>
</div>

Fision is a self-hosted stem player. You just send a song to your
[FisionServer](https://github.com/jotapemj/Fision-Server), it comes back as separate
stems with chords, beats and lyrics, and this app plays them through a native
engine where every stem can be mixed, effected and even placed in 3D space.

<h3 align="center">Download the latest release!</h3>

<p align="center">
  <a href="https://github.com/jotapemj/Fision-AppAndroid/releases/latest/download/fision-latest.apk"><img src="https://img.shields.io/badge/Download-APK-27B397?style=for-the-badge&logo=android&logoColor=white" alt="Download the APK"></a>
  <a href="https://github.com/jotapemj/Fision-AppAndroid/releases/latest"><img src="https://img.shields.io/github/v/release/jotapemj/Fision-AppAndroid?style=for-the-badge&label=Latest&color=555555" alt="Latest release"></a>
</p>

<p align="center">
  <a href="docs/GUIDE.md"><b>User guide</b></a> ·
  <a href="docs/TECHNICAL.md"><b>Technical notes</b></a> ·
  <a href="https://github.com/jotapemj/Fision-Server"><b>FisionServer</b></a> ·
  <a href="https://github.com/jotapemj/Fision-AppAndroid/releases"><b>Releases</b></a> ·
  <a href="https://github.com/jotapemj/Fision-AppAndroid/issues"><b>Report an issue</b></a>
</p>

<br>

<div align="center">
  <img src="./docs/readme/screens-hero.png" width="860" alt="Fision screens: song library, stem mixer with the chord strip, synced lyrics and the 3D audio scene">
</div>

<br>


<div align="center">
<details>
  <summary><b>Watch the demo</b></summary>

https://github.com/user-attachments/assets/fb8d2289-e46d-4a26-9af3-b5bbab4d9315

</details>
</div>


## Features

<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/readme/features-dark.svg">
    <img src="./docs/readme/features-light.svg" width="860" alt="Fision features: Self-hosted, Stem separation, Stem mixer, 3D audio, Synced Lyrics, Chord diagrams, Pitch and tempo, Dynamic metronome, Android Auto Compatible">
  </picture>
</div>

## Also

* **Stem effects**: filter, tempo-synced echo and reverb on each stem.
* **Export** the mix or each stem to WAV, AAC or M4A.
* **Playlists** stored on your server, playable offline.
* **Chord notation** in American or Latin, with sharps or flats, transposed with the pitch.
* **Chords over the lyrics** and a **chord grid** of the whole song.
* **Background animation** with optional haptics on tempo.
* **Share song to Fision** compatible.
* **Local mode** to play song folders without a server.
* **10 languages**

<details>
<summary><b>More screenshots</b></summary>
<br>
<div align="center">
  <img src="./docs/readme/screens-more-1.png" width="860" alt="Chord diagrams, stems dancing in the 3D scene, stem options with effects, and the dynamic metronome">
  <br><br>
  <img src="./docs/readme/screens-more-2.png" width="860" alt="Playlist with downloaded and cloud songs, library search with filter chips, export dialog and settings">
  <br><br>
  <img src="./docs/readme/screens-auto.png" width="860" alt="Android Auto: the downloaded songs and the player with the karaoke and backing vocals buttons">
</div>
</details>

## Getting started

1. Run a [FisionServer](https://github.com/jotapemj/Fision-Server) on a computer you
   control, ideally reachable through Tailscale or HTTPS.
2. Install the app: download the APK from the
   [latest release](https://github.com/jotapemj/Fision-AppAndroid/releases/latest)
   or build it yourself (see [Building](#building)). It needs Android 8.0 or newer.
3. Sign in with the server address and your account, add a song and pick how
   to separate it.

### Try it without a server

A demo song ready to play, already split into stems with chords, beats,
metronomes and synced lyrics: "Airplane Mode" by Josh Woodward, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

In **Local mode** the library shows it pinned on top with a dark cover: tap
it to download it from GitHub and import it in one step (also from
**Settings → About**). To do the same by hand:

1. Download
   [`fision-demo.zip`](https://github.com/jotapemj/Fision-Server/releases/download/v2.1.0/fision-demo.zip)
   (about 170 MB) on the phone.
2. Extract it, for example with the Files app. It holds one folder named
   with a long hash, and inside it a `stems` folder: that one is the song.
3. Start Fision in **Local mode** (on the first launch, or from the account
   row in Settings).
4. Tap **Import folder**, confirm, and pick the `stems` folder in the system
   folder picker.

The app copies only the files the player reads and checks every one of
them before the song is added: all the audio is decoded, the stems must have
the same length, and the chord, beat and lyrics files must parse. Other files
in the folder are ignored. Once imported, the zip and the extracted folder
can be deleted.

The [user guide](docs/GUIDE.md) walks through every screen, and the
[technical notes](docs/TECHNICAL.md) cover the engine and the song format.

## Building

A standard Android Studio project with Gradle, plus the Android NDK and
CMake for the C++ engine. Open it in Android Studio, let Gradle sync and run
the `app` configuration on a device with Android 8.0 (API 26) or newer.

## A bit of history: road to Fision

Fision is a project I have been thinking about since 2020, when the **Demucs** and **Spleeter** audio separation models first came out. When I discovered those models, I was fascinated by the idea that a song could be separated into different instruments, or turned into an instrumental track with almost no artifacts (you could still hear some back then, but to me it was amazing). My knowledge of Python, patience, and some free time allowed me to build a prototype of what **I had in mind**: a player that would let me remove instruments in real time and display chords for backing tracks. That’s how the first Fision prototype came about, which initially went by other, more primitive names, such as *“AIMS”* (*Artificial Intelligence Music Separator*) or, later on, *FisionAI*.

<details>
<summary><b>Fision timeline</b></summary>

**2020: first experiments.** Demucs and Spleeter running on a laptop
GTX 1050, with the separated stems mixed by hand in a DAW.

**2022-2023: FisionAI.** A desktop app in **Python** and **Qt**: a song library, a four-stem mixer, **Demucs** separation on the GPU, beats and chords first with **Chordino** and later with **madmom**, a metronome at half, normal and double speed, and chord diagrams for guitar, piano and ukulele. **Changing pitch or tempo live** was out of reach for me at that time.

<div align="center">
  <img src="./docs/readme/journey/fisionai_mosaic.png" width="860" alt="FisionAI: splash screen, mixer, library, chord settings, song splitter and settings"></div>

**2023: first Android app.** I ported the logic of the Python program to Android. It was a primitive first version. No server at all: everything offline, with the song folders copied straight to the phone (the origin of today's local mode), and one `MediaPlayer` per stem. Keeping those players in sync was a constant headache.

<div align="center">
  <img src="./docs/readme/journey/android_2023.png" width="260" alt="First Android version of Fision, 2023">
</div>

**2024: AudioEngineDemo.** The first Android test of a native engine with
Oboe and SoundTouch: four stems in sync with volume, pan, and live pitch and tempo. That was the turning point, as it became the core of the current engine. No more MediaPlayer.

<div align="center">
  <img src="./docs/readme/journey/audioenginedemo.png" width="260" alt="AudioEngineDemo layout">
  <p><sub>Oboe audio engine demo app for testing.</sub></p>
</div>

**Today: Fision.** The engine grew into the app, and the Python pipeline
moved to FisionServer.

</details>

## Thanks

> Fision is a personal project I have been working on slowly for years, and one
> I hold dear. I am proud to have finally reached the project I had been dreaming about for so long.
>
> Thanks to everyone who had the patience to listen to me, test the app and suggest improvements. Thanks as well to today's LLMs, like Claude, which helped me a lot with the hardest parts, such as server communication and maintenance.
>
> If this project is useful to you, a star, an issue or an idea means a lot.
> Thank you.

## Credits

Fision builds on these projects. Please respect their licenses if you reuse
code from this repository.

**Audio**

- [Oboe](https://github.com/google/oboe): low-latency audio. Apache 2.0.
- [SoundTouch](https://www.surina.net/soundtouch/): pitch and tempo. LGPL 2.1.
- [dr_flac](https://github.com/mackron/dr_libs): FLAC decoding. MIT-0 / Public Domain.
- [KEMAR HRTF measurements](https://sound.media.mit.edu/resources/KEMAR.html),
  MIT Media Lab: free with no restrictions on use, citing the authors.

  > Gardner, W. G., and Martin, K. D. (1995). "HRTF measurements of a KEMAR."
  > Journal of the Acoustical Society of America, 97(6), 3907-3908.
- Downbeat click sounds: recorded by Ludwig Peter Müller, December 2020.
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

**App**

- AndroidX, Material Components, Room, OkHttp, Retrofit and Gson: Apache 2.0.
- [Filament](https://github.com/google/filament) and
  [SceneView](https://github.com/SceneView/sceneview-android): 3D scene. Apache 2.0.
- Listener head model of the 3D scene, generated with [Meshy](https://www.meshy.ai).
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- [chords-db](https://github.com/tombatossals/chords-db): guitar and ukulele
  chord shapes. MIT.
- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) and
  [MusGlyphs](https://www.notationcentral.com/product/musglyphs/): fonts. SIL Open Font License.
- [Material Icons](https://fonts.google.com/icons): car icon of the feature
  grid. Apache 2.0.

**Demo song and screenshots**

- "Airplane Mode", the demo song FisionServer adds to every new account, and
  "Faded War", both by [Josh Woodward](https://www.joshwoodward.com) with the
  cover of the album *The Beautiful Machine*.
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Stems, chords,
  beats and lyrics are derived from the recordings. Other covers in the
  screenshots are pixelated, and so are other apps' icons in the Android
  Auto ones.

**Server pipeline** (runs on FisionServer, listed for attribution)

- Demucs and BS-RoFormer (via audio-separator): stem separation.
- BTC (Bidirectional Transformer for Chord recognition): chords.
- beat_this: beats and downbeats.
- Whisper (via faster-whisper): lyrics transcription.

Android Auto is a trademark of Google LLC.

## License

Copyright (C) 2026 Jotapeveloper

Fision is free software: you can redistribute it and/or modify it under the
terms of the [GNU General Public License](LICENSE) as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version. Any work based on it must keep this notice and be released
under the same license.

Third-party components keep their own licenses (see [Credits](#credits)).
The KEMAR HRTF data is free to use, including commercially, provided its
authors are cited.
