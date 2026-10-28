"""Phase C: one playlist created.

Before running: the demo account has one playlist (with its songs).
"""
from fision_adb import *

prepare()
unpark_songs()
launch()
tap(rid="tab_playlists", settle=2.5)
shot("c1_playlists")

# First playlist of the list.
root = dump()
rv = find(rid="rv_playlists", root=root)
if rv is None or len(list(rv)) == 0:
    sys.exit("No hay playlists en la lista.")
tap_xy(*center(rv[0]), settle=2.5)
shot("c2_playlist_detail")
print("Fase C hecha.")
