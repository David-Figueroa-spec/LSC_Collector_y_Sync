"""Graba poses 3D de tu mano con la webcam y las guarda en assets/poses_letras.json.

Uso:  python herramientas/capturar_poses.py [indice_camara]
Teclas: una letra (A-Z) o 1 (para Ñ) elige la letra, ESPACIO graba 30 fotogramas, ESC sale.
Requiere: pip install mediapipe opencv-python numpy
"""
import json
import os
import sys
import time

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.environ.get("LSC_ASSETS", os.path.join(RAIZ, "app", "src", "main", "assets"))
MODELO = os.path.join(ASSETS, "hand_landmarker.task")
SALIDA = os.path.join(ASSETS, "poses_letras.json")
FRAMES = 30
FRAMES_MOV = 12      # fotogramas que se guardan en una secuencia
DURACION_MOV = 2.0   # segundos que dura la grabación de movimiento

cam = int(sys.argv[1]) if len(sys.argv) > 1 else 0
opts = vision.HandLandmarkerOptions(
    base_options=mpp.BaseOptions(model_asset_path=MODELO),
    num_hands=1,
    running_mode=vision.RunningMode.VIDEO,
)
det = vision.HandLandmarker.create_from_options(opts)
cap = cv2.VideoCapture(cam, cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY)


def guardar(letra, prom):
    data = {}
    if os.path.exists(SALIDA):
        with open(SALIDA, encoding="utf-8") as f:
            data = json.load(f)
    if isinstance(prom, list):  # secuencia: lista de poses
        data[letra] = [[round(float(v), 5) for v in f] for f in prom]
    else:
        data[letra] = [round(float(v), 5) for v in prom]
    with open(SALIDA, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"Guardada {letra} ({len(data)} letras en el archivo)")


letra, muestras, grabando = None, [], False
mov, t0 = False, 0.0
while True:
    ok, frame = cap.read()
    if not ok:
        print("No se pudo leer la cámara")
        break
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    res = det.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb),
                               int(time.monotonic() * 1000))
    arr = None
    if res.hand_world_landmarks:
        arr = np.array([[p.x, p.y, p.z] for p in res.hand_world_landmarks[0]],
                       dtype=np.float32).flatten()
        h, w = frame.shape[:2]
        for p in res.hand_landmarks[0]:
            cv2.circle(frame, (int(p.x * w), int(p.y * h)), 4, (0, 255, 0), -1)
    if grabando and mov:
        if arr is not None:
            muestras.append(arr)
        if time.monotonic() - t0 >= DURACION_MOV:
            if len(muestras) >= FRAMES_MOV:
                idx = np.linspace(0, len(muestras) - 1, FRAMES_MOV).astype(int)
                guardar(letra, [muestras[i] for i in idx])
            else:
                print("Muy pocos fotogramas con mano. Repite.")
            grabando, muestras = False, []
    elif grabando and arr is not None:
        muestras.append(arr)
        if len(muestras) >= FRAMES:
            guardar(letra, np.mean(muestras, axis=0))
            grabando, muestras = False, []
    modo = "MOVIMIENTO" if mov else "pose fija"
    estado = ("GRABANDO %d" % len(muestras)) if grabando else "ESPACIO = grabar"
    mano = "mano OK" if arr is not None else "SIN MANO"
    cv2.putText(frame, "Letra: %s | %s | %s | %s" % ("enye" if letra == "Ñ" else letra, modo, estado, mano),
                (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
    cv2.imshow("capturar_poses", frame)
    k = cv2.waitKey(1) & 0xFF
    if k == 27:
        break
    if k == 9:
        mov = not mov
        print("Modo:", "MOVIMIENTO (2 s)" if mov else "pose fija")
    elif k == 32 and letra and not grabando:
        grabando, muestras, t0 = True, [], time.monotonic()
    elif k == ord("1"):
        letra = "Ñ"
    elif 97 <= k <= 122:
        letra = chr(k).upper()

cap.release()
cv2.destroyAllWindows()
