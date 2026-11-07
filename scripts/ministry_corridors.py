#!/usr/bin/env python3
"""Bakanlığın tüm ortalama hız koridorlarını toplar (rotasız sürüşte koridor takibi için).

Bakanlık koridorları yalnızca rota sorgusunda veriyor. Her il merkezinden en yakın K il merkezine
rota sorulur; dönen SpeedTunnels birleştirilir. Sunucuyu yormamak için istekler arası bekleme var,
yarıda kalırsa ara sonuç (<çıktı>.state.json) kaldığı yerden sürer.

Kullanım: python3 scripts/ministry_corridors.py app/src/main/assets/ministry/corridors_tr.json
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://www.icisleri.gov.tr/ISAYWebPart/PolGenControlPointV2"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (compatible; RadarNav/0.2; +https://github.com/emocantemocan/radar)",
    "X-Requested-With": "XMLHttpRequest",
    "Referer": "https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari",
}
DELAY = 4.0
K = 5


def call(path, data=None, tries=4):
    for i in range(tries):
        try:
            body = urllib.parse.urlencode(data).encode() if data else None
            req = urllib.request.Request(BASE + path, data=body, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=60) as r:
                j = json.loads(r.read().decode("utf-8"))
            if isinstance(j, dict) and j.get("success") is False:
                raise IOError(j.get("message"))
            return j
        except Exception as e:  # geçici hata: bekle, tekrar dene
            print(f"  hata ({i + 1}/{tries}) {path}: {e}", file=sys.stderr)
            time.sleep(DELAY * (i + 2))
        finally:
            time.sleep(DELAY)
    return None


def dist(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def capitals():
    src = open(os.path.join(os.path.dirname(__file__), "..", "core/src/main/kotlin/com/radarnav/core/ministry/Provinces.kt"),
               encoding="utf-8").read()
    return {m[0]: (float(m[1]), float(m[2])) for m in re.findall(r'"([^"]+)" to LatLng\(([\d.]+), ([\d.]+)\)', src)}


def main(out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    state_path = out + ".state.json"
    st = json.load(open(state_path)) if os.path.exists(state_path) else {"districts": {}, "done": [], "corridors": {}}
    caps = capitals()
    cities = call("/GetCities")
    cities = cities.get("data", cities) if isinstance(cities, dict) else cities
    ids = {c["Name"]: c["Id"] for c in cities}
    # Her il için il merkezine en yakın ilçe
    for name, ll in caps.items():
        if name in st["districts"] or name not in ids:
            continue
        ds = call(f"/GetDistricts?cityId={ids[name]}")
        if not ds:
            continue
        d = min(ds, key=lambda x: dist(ll, (x["Latitude"], x["Longitude"])))
        st["districts"][name] = [d["Id"], d["Latitude"], d["Longitude"]]
        json.dump(st, open(state_path, "w"), ensure_ascii=False)
    print(f"{len(st['districts'])} il merkezi", file=sys.stderr)

    names = sorted(st["districts"])
    pairs = set()
    for a in names:
        near = sorted((n for n in names if n != a), key=lambda n: dist(caps[a], caps[n]))[:K]
        for b in near:
            pairs.add(tuple(sorted((a, b))))
    todo = [p for p in sorted(pairs) if "|".join(p) not in st["done"]]
    print(f"{len(pairs)} il çifti, {len(todo)} kaldı", file=sys.stderr)
    for i, (a, b) in enumerate(todo):
        da, db = st["districts"][a], st["districts"][b]
        j = call("/CreateRoute", {
            "fromLatitude": da[1], "fromLongitude": da[2], "toLatitude": db[1], "toLongitude": db[2],
            "fromDistrictId": da[0], "toDistrictId": db[0],
        })
        if j is None:
            continue
        for t in (j.get("data") or {}).get("SpeedTunnels") or []:
            st["corridors"][str(t["id"])] = {k: t.get(k) for k in (
                "id", "name", "provinceName", "districtName", "activity", "speedLimit", "length",
                "startLatY", "startLonX", "endLatY", "endLonX")}
        st["done"].append(f"{a}|{b}")
        json.dump(st, open(state_path, "w"), ensure_ascii=False)
        print(f"[{i + 1}/{len(todo)}] {a}-{b}: toplam {len(st['corridors'])} koridor", file=sys.stderr)

    os.makedirs(os.path.dirname(out), exist_ok=True)
    data = {"generatedAt": time.strftime("%Y-%m-%d"), "corridors": sorted(st["corridors"].values(), key=lambda c: c["id"])}
    json.dump(data, open(out, "w"), ensure_ascii=False, separators=(",", ":"))
    print(f"{len(data['corridors'])} koridor -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])
