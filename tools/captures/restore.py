"""After the last phase: app language back to the system one, real status bar."""
from fision_adb import restore, unpark_songs

unpark_songs(only=[])
restore()
print("Móvil restaurado.")
