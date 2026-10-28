"""Phase D: the player, on one downloaded song with lyrics and chords.

Before running: library state does not matter; the song is downloaded.
Env vars: SONG (title text to tap in the library, default "Airplane Mode"),
SEEK (fraction of the song for the main shot, default 0.35), LYRICS_SEEK
(fraction where a line is being sung, default 0.38).

uiautomator only reads a quiet screen, so every lookup happens paused; the
shots that need movement play for a moment from coordinates read before.
"""
import os
from fision_adb import *

song = os.environ.get("SONG", "Airplane Mode")
seek = float(os.environ.get("SEEK", "0.35"))
lyrics_seek = float(os.environ.get("LYRICS_SEEK", "0.38"))


def bounds(node):
    return tuple(map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds")).groups()))


def need(rid, root=None):
    n = find(rid=rid, root=root)
    if n is None:
        sys.exit(f"No encuentro en pantalla: {rid}")
    return n


def seek_to(bar, fraction):
    x1, y1, x2, y2 = bar
    tap_xy(int(x1 + (x2 - x1) * fraction), (y1 + y2) // 2, settle=1.0)


def play_shot(name, seconds=3.0):
    media_play()
    wait(seconds)
    shot(name)
    media_pause()
    wait(1.0)


prepare()
unpark_songs()
launch()
tap(text=song, contains=True, settle=8.0)          # opens the player, stems land
media_pause()
wait(1.5)
bar = bounds(need("sb_progressBar"))

seek_to(bar, seek)
play_shot("d1_player")

tap(rid="b_chord", settle=1.2)
tap(desc="Chord diagrams", settle=2.5)
shot("d2_chord_diagrams")
tap(rid="b_chord", settle=2.0)                      # back to the stems

tap(rid="b_lyrics", settle=3.0)
seek_to(bar, lyrics_seek)
play_shot("d3_lyrics", 3.5)
tap(rid="b_lyrics", settle=2.0)

tap(rid="b_3d", settle=9.0)                         # scene builds behind its spinner
root = dump()
sw = need("sw_3d_enabledScene", root)
was_on = sw.get("checked") == "true"
sw_xy = center(sw)
if not was_on:
    tap_xy(*sw_xy, settle=2.5)
shot("d4_scene_3d")

dance_xy = center(need("b_3d_danceScene"))
tap_xy(*dance_xy, settle=2.0)
shot("d5_dance_dialog")
tap(rid="b_dance_start", settle=2.0)
play_shot("d6_dance", 4.0)
tap_xy(*dance_xy, settle=1.5)                       # stop the dance
if not was_on:
    tap_xy(*sw_xy, settle=1.5)                      # leave 3D as it was
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
