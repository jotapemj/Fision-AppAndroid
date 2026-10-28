"""Phase E: settings and the account dialog.

Before running: signed in as demo. Env var USER_NAME (default "demo") is the
text of the account header to tap.
"""
import os
from fision_adb import *

user = os.environ.get("USER_NAME", "demo")

prepare()
unpark_songs()
launch()
tap(rid="b_settings", settle=2.0)
shot("e1_settings")
tap(text=user, settle=2.0)
shot("e2_account")
back()
print("Fase E hecha.")
