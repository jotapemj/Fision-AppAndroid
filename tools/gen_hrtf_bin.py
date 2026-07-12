"""
Genera el fichero binario `hrtf_horizontal.bin` a partir del dataset KEMAR MIT
compact (múltiples elevaciones).

Formato de salida FHR2:
    uint32  magic         = 0x32524846 ("FHR2")
    uint32  numElevations
    uint32  numAzimuths
    uint32  hrirLength    (128)
    float32 sampleRate    (44100)
    float32 elevations[numElevations]              // grados
    float32 data[numElev][numAz][2][hrirLength]    // [elev][az][L,R][sample]

Uso (default: recorrer todos los elevXX que existan bajo --src):
    python gen_hrtf_bin.py --src <ruta>/compact --out <ruta>/hrtf_horizontal.bin

Con paso de azimuth custom (default 5°):
    python gen_hrtf_bin.py --src <ruta>/compact --out <ruta>/hrtf_horizontal.bin --step 10

Los ficheros KEMAR compact son raw .dat (16-bit BE, stereo interleaved, 128 samples
por canal). En cada carpeta elevXX solo hay azimuts [0..180] (lado derecho);
para el otro lado usamos simetría lateral (L/R swapped).
"""

import argparse
import os
import re
import struct
import sys

import numpy as np

MAGIC = 0x32524846   # "FHR2"
SR    = 44100.0
HRIR_LEN = 128

DAT_PAT = re.compile(r"^H(-?\d+)e(\d+)a\.dat$", re.IGNORECASE)

def read_dat_stereo(path):
    with open(path, "rb") as fo:
        raw = fo.read()
    if len(raw) < HRIR_LEN * 2 * 2:
        raise RuntimeError(f"{path}: tamaño incorrecto {len(raw)}")
    interleaved = np.frombuffer(raw[:HRIR_LEN * 4], dtype=">i2").astype(np.float32) / 32768.0
    L = interleaved[0::2]
    R = interleaved[1::2]
    return L, R

def scan_elevation_dir(elev_dir):
    """Escanea una carpeta elevXX y devuelve {az_int: (L, R)} para el elev de esa carpeta."""
    files = {}
    for fn in os.listdir(elev_dir):
        m = DAT_PAT.match(fn)
        if not m: continue
        az = int(m.group(2))
        files[az] = os.path.join(elev_dir, fn)
    return files

def nearest_az(keys, target):
    return min(keys, key=lambda k: min(abs(k - target), 360 - abs(k - target)))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src",  required=True, help="Carpeta compact/ (con elevXX/ dentro)")
    ap.add_argument("--out",  required=True, help="Ruta del binario de salida")
    ap.add_argument("--step", type=int, default=5, help="Paso azimuth en grados (default 5)")
    args = ap.parse_args()

    # Discover elevXX folders
    elev_dirs = []
    for entry in sorted(os.listdir(args.src)):
        m = re.match(r"^elev(-?\d+)$", entry, re.IGNORECASE)
        if not m: continue
        elev = int(m.group(1))
        path = os.path.join(args.src, entry)
        if not os.path.isdir(path): continue
        elev_dirs.append((elev, path))
    if not elev_dirs:
        print(f"No hay carpetas elevXX/ bajo {args.src}", file=sys.stderr)
        sys.exit(1)
    elev_dirs.sort(key=lambda x: x[0])
    elevations = [e for e, _ in elev_dirs]
    print(f"Elevaciones detectadas: {elevations}")

    step = args.step
    num_az = 360 // step
    print(f"Generando {len(elevations)} elevaciones × {num_az} azimuts cada {step}°")

    all_data = np.zeros((len(elevations), num_az, 2, HRIR_LEN), dtype=np.float32)

    for ei, (elev, edir) in enumerate(elev_dirs):
        files = scan_elevation_dir(edir)
        if not files:
            print(f"  elev {elev}: sin ficheros, saltando", file=sys.stderr)
            continue
        keys = list(files.keys())
        print(f"  elev {elev}: {len(keys)} azimuts nativos")
        for j in range(num_az):
            az = j * step
            if 0 <= az <= 180:
                src_az  = az
                swap_lr = False
            else:
                src_az  = 360 - az
                swap_lr = True
            picked = files.get(src_az) or files[nearest_az(keys, src_az)]
            L, R = read_dat_stereo(picked)
            if swap_lr:
                L, R = R, L
            all_data[ei, j, 0, :] = L[:HRIR_LEN]
            all_data[ei, j, 1, :] = R[:HRIR_LEN]

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "wb") as fo:
        fo.write(struct.pack("<I", MAGIC))
        fo.write(struct.pack("<I", len(elevations)))
        fo.write(struct.pack("<I", num_az))
        fo.write(struct.pack("<I", HRIR_LEN))
        fo.write(struct.pack("<f", SR))
        fo.write(np.array(elevations, dtype=np.float32).tobytes())
        fo.write(all_data.tobytes(order="C"))
    total = os.path.getsize(args.out)
    print(f"OK: {args.out} ({total} bytes)")

if __name__ == "__main__":
    main()
