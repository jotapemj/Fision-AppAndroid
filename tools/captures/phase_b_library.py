"""Phase B: library with downloaded and cloud songs, no playlists.

Before running: songs on the server for demo, some of them downloaded on the
phone, none being processed unless that state is wanted in the capture.
Optional: SEARCH env var with the text for the search capture (default "ar"; letters outside a-f so no folder hash matches).
"""
import os
from fision_adb import *

query = os.environ.get("SEARCH", "ar")

prepare()
unpark_songs()
launch()
shot("b1_library")

tap(rid="et_find", settle=1.0)
type_text(query)
wait(1.0)
back(0.8)                       # hide the keyboard, keep the results
shot("b2_search")
back()                          # close the search

tap(rid="tab_playlists", settle=2.0)
shot("b3_playlists_none")
print("Fase B hecha.")
