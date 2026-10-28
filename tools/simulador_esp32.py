#!/usr/bin/env python3
"""
SmartStock IoT - Simulador del ESP32 (para probar sin hardware).

Envia lecturas de temperatura, humedad y peso a la API con el mismo formato
y las mismas cabeceras que el firmware real. Solo usa la biblioteca estandar.

Uso:
  python simulador_esp32.py                         # 5 s entre lecturas, 50 g por unidad, 20 unidades
  python simulador_esp32.py --unidades 12           # simula que se retiraron productos (genera discrepancia)
  python simulador_esp32.py --temp 35               # temperatura fuera de rango (genera alerta)
  python simulador_esp32.py --duplicar              # reenvia cada lote 2 veces (prueba anti-duplicados)
  python simulador_esp32.py --ciclos 1              # un solo envio

Variables de entorno opcionales: SMARTSTOCK_URL, DEVICE_CODE, DEVICE_KEY
"""
import argparse
import json
import os
import random
import time
import urllib.error
import urllib.request

p = argparse.ArgumentParser(description="Simulador ESP32 para SmartStock IoT")
p.add_argument("--url", default=os.environ.get("SMARTSTOCK_URL", "http://localhost:8080"))
p.add_argument("--codigo", default=os.environ.get("DEVICE_CODE", "ESP32-01"))
p.add_argument("--clave", default=os.environ.get("DEVICE_KEY", "dev-device-key-cambiar"))
p.add_argument("--periodo", type=float, default=5.0, help="segundos entre lecturas")
p.add_argument("--ciclos", type=int, default=0, help="0 = infinito")
p.add_argument("--temp", type=float, default=21.0)
p.add_argument("--hum", type=float, default=50.0)
p.add_argument("--unidades", type=float, default=20, help="unidades sobre la balanza")
p.add_argument("--peso-unitario", type=float, default=50.0)
p.add_argument("--tara", type=float, default=0.0, help="peso del recipiente (g)")
p.add_argument("--seq-inicial", type=int, default=int(time.time()), help="por defecto la hora actual, para no repetir seq")
p.add_argument("--duplicar", action="store_true")
a = p.parse_args()


def enviar(lote):
    datos = json.dumps({"lecturas": lote}).encode()
    req = urllib.request.Request(a.url + "/api/iot/lecturas", data=datos, method="POST", headers={
        "Content-Type": "application/json",
        "X-Device-Code": a.codigo,
        "X-Device-Key": a.clave,
    })
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            resp = json.loads(r.read())
            ms = (time.time() - t0) * 1000
            print(f"  200 OK ({ms:.0f} ms) guardadas={resp['guardadas']} duplicadas={resp['duplicadas']} "
                  f"rechazadas={resp['rechazadas']} reenviar={resp.get('reenviar', 0)} "
                  f"ventilador={'ENCENDIDO' if resp.get('ventilador') else 'apagado'}")
            for res in resp["resultados"]:
                if res["resultado"] != "GUARDADA" or res["detalle"] != "VALIDA":
                    print(f"     {res['tipo']} seq={res['seq']}: {res['resultado']} ({res['detalle']})")
            return True
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode(errors='replace')}")
    except Exception as e:  # sin conexion, timeout...
        print(f"  Error de conexion: {e}")
    return False


pendientes = []
seq = a.seq_inicial
ciclo = 0
print(f"Simulando {a.codigo} -> {a.url}  (Ctrl+C para detener)")
while a.ciclos == 0 or ciclo < a.ciclos:
    ciclo += 1
    seq += 1
    ts = int(time.time())
    peso = a.tara + a.unidades * a.peso_unitario + random.uniform(-2, 2)  # ruido de la celda
    pendientes += [
        {"tipo": "TEMPERATURA", "valor": round(a.temp + random.uniform(-0.3, 0.3), 2), "seq": seq, "ts": ts},
        {"tipo": "HUMEDAD", "valor": round(a.hum + random.uniform(-1, 1), 2), "seq": seq, "ts": ts},
        {"tipo": "PESO", "valor": round(peso, 2), "seq": seq, "ts": ts},
    ]
    print(f"[{time.strftime('%H:%M:%S')}] seq={seq} T={a.temp} H={a.hum} peso~{peso:.1f} g "
          f"(pendientes {len(pendientes)})")
    lote = pendientes[:30]
    if enviar(lote):
        if a.duplicar:
            print("  reenvio del mismo lote (prueba anti-duplicados):")
            enviar(lote)
        pendientes = pendientes[30:]
    if a.ciclos == 0 or ciclo < a.ciclos:
        time.sleep(a.periodo)
