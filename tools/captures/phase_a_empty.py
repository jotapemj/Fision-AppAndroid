"""Phase A: demo account with no songs and no playlists.

Before running: on the server the demo account has no songs and no playlists,
app signed in as demo. Downloaded songs get parked off the app folder.
"""
from fision_adb import *

prepare()
park_songs()
launch()
# Shine of the add bar: give it time to sweep once.
wait(6)
shot("a1_library_empty")

tap(rid="tab_playlists", settle=2.0)
shot("a2_playlists_empty_no_songs")
print("Fase A hecha.")
