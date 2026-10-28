"""Phase D: the player, on one downloaded song with lyrics and chords.

Before running: library state does not matter; the song is downloaded.
Env vars: SONG (title text to tap in the library, default "Hold the Line"),
SEEK (fraction of the song to jump to, default 0.35).
"""
import os
from fision_adb import *

song = os.environ.get("SONG", "Hold the Line")
seek = float(os.environ.get("SEEK", "0.35"))


def seek_to(fraction):
    bar = find(rid="sb_progressBar")
    if bar is None:
        sys.exit("No encuentro la barra de progreso.")
    x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bar.get("bounds")).groups())
    tap_xy(int(x1 + (x2 - x1) * fraction), (y1 + y2) // 2, settle=1.0)


prepare()
unpark_songs()
launch()
tap(text=song, contains=True, settle=8.0)          # opens the player, stems land
seek_to(seek)
wait(3.0)                                           # chords and lyrics catch up
shot("d1_player")

# Paused from here: uiautomator needs a quiet screen to read it.
media_pause()
wait(1.0)

tap(rid="b_chord", settle=1.2)
tap(desc="Chord diagrams", settle=2.5)
shot("d2_chord_diagrams")
tap(rid="b_chord", settle=2.0)                      # back to the stems

tap(rid="b_lyrics", settle=3.0)
shot("d3_lyrics")
tap(rid="b_lyrics", settle=2.0)

tap(rid="b_3d", settle=9.0)                         # scene builds behind its spinner
shot("d4_scene_3d")
if tap(rid="b_3d_danceScene", settle=2.0, required=False):
    shot("d5_dance_dialog")
    tap(rid="b_dance_start", settle=5.0)
    shot("d6_dance")
    tap(rid="b_3d_danceScene", settle=1.5)          # stop the dance
tap(rid="b_3d", settle=2.5)

tap(rid="ib_options", index=0, settle=2.0)
shot("d7_stem_options")
back()

tap(rid="b_metronome", settle=2.0)
shot("d8_metronome")
back()

tap(rid="b_pitch", settle=2.0)
shot("d9_pitch")
back()

tap(rid="b_menu", settle=1.5)
shot("d10_drawer")
tap(rid="drawer_item_export", settle=2.0)
shot("d11_export")
back()
print("Fase D hecha.")
