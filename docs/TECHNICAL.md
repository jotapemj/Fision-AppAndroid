# Fision technical notes

How the app plays and stores songs. For the overview see the
[README](../README.md); for using the app see the [user guide](GUIDE.md).

## Audio engine

The engine is C++ on [Oboe](https://github.com/google/oboe). One audio
stream drives every stem, so all stems are mixed sample by sample in the
same callback and always stay in phase. A soft-knee limiter sits at the end
of the mix; exports use the same curve.

Pitch and tempo use [SoundTouch](https://www.surina.net/soundtouch/)
(WSOLA). SoundTouch runs on every stem all the time, even at the default
pitch and tempo: stems that skipped it would drift out of phase with the
ones that go through it. The drums stem passes through without being
pitched.

Per-stem effects (filter, tempo-synced echo, reverb send) run in the engine
after time stretching and before volume, pan and 3D. One reverb per room
size is shared by every stem. Effects are kept in memory only.

Solo is a gate per stem in the engine, separate from the volume: while any
stem is soloed the others are silenced with a 20 ms ramp, in normal and 3D
playback alike, and their sliders are left untouched. It is not saved.

3D audio convolves each stem with a 128-tap HRTF per ear built from the MIT
KEMAR measurements, interpolating azimuth and elevation. Without the HRTF
data the engine falls back to an ITD + ILD approximation.

FLAC is decoded with
[dr_flac](https://github.com/mackron/dr_libs/blob/master/dr_flac.h).

## Stem files

The server packs stems as FLAC by default; WAV (16, 24 or 32-bit PCM or
32-bit float, mono or stereo) is also accepted. WAV is lossless as well but
about twice as large, and brings nothing when the source is a lossy
file.

Stems are matched by file name (case-insensitive, without extension)
against a catalog of known stems; other files are ignored.

## Song folder

Each song is a folder inside `Songs/` in the app's external storage.
Downloaded songs are named after the hash the server assigns; folders added
by hand can have any name and stay local only.

```
SongName/
├── bass.flac
├── drums.flac
├── other.flac
├── vocals.flac
├── (extra stems: guitar, piano, brass, kick, snare, toms, hh, ride,
│    crash, vocals2…)
├── metronome.flac
├── metronome_half.flac
├── metronome_double.flac
├── chords.txt         (advanced chord chart, one "CHORD TIME" per line)
├── chords_basic.txt   (optional, basic chord chart)
├── beats.txt          (first line: global BPM, then one beat time per line)
├── downbeats.txt      (optional, one downbeat time per line)
├── metadata.txt       (optional, "Title: ...", "Artist: ...")
├── artcover.png       (optional, cover for the notification and the UI)
└── lyrics.json        (optional, cached transcription)
```

### Missing files

- No `chords_basic.txt`: the basic / advanced switch is disabled and the
  advanced chart is used.
- No `downbeats.txt`: bar lines are inferred from the beats and the global
  BPM.
- No `metadata.txt` or `artcover.png`: the folder name is the title and a
  generic cover is shown.

## Persistence

Per-song settings live in a Room (SQLite) database: volume (0 is muted)
and pan per stem, pitch, tempo, metronome subdivision and volume, chord chart
variant, 3D on or off, and the 3D position and mute of each stem. Migrations between
versions keep existing data.

Global preferences (chord notation, sort order, downbeat sample, repeat
mode, autoplay, animated background…) live in `SharedPreferences`. The
session token is kept in encrypted preferences and excluded from backups.

## Export

Exports are rendered offline in Java from the stem files, with volume, pan
and, optionally, the effects and the same limiter as playback. Pitch, tempo
and solo are not applied. Files go to `Music/Fision/<Artist - Title>/` with
a timestamp in the name, as WAV (16-bit), AAC (ADTS) or M4A.

## Background playback

Playback runs in a foreground service that exposes a `MediaSession` and acts
as the media browser for Android Auto. The session starts inactive, only a
user play activates it, and it stops after 10 minutes paused with no app
screen visible, so a car or headset cannot wake the app on its own.
