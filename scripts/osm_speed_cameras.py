"""Türkiye OSM dosyasından tüm sabit hız kameralarını (highway=speed_camera) Overpass JSON biçiminde çıkarır.
Kullanım: venv/bin/python -I extract.py turkey.osm.pbf cikti.json"""
import json, sys, time
import osmium

class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__(); self.out = []
    def node(self, n):
        if n.tags.get("highway") == "speed_camera":
            tags = {k: n.tags.get(k) for k in ("maxspeed", "direction", "name", "ref") if n.tags.get(k)}
            self.out.append({"type": "node", "id": n.id, "lat": round(n.location.lat, 6), "lon": round(n.location.lon, 6), "tags": tags})

h = H(); h.apply_file(sys.argv[1])
doc = {"generated": time.strftime("%Y-%m-%d"), "attribution": "© OpenStreetMap katkıcıları, ODbL 1.0", "elements": h.out}
json.dump(doc, open(sys.argv[2], "w"), ensure_ascii=False, separators=(",", ":"))
print(len(h.out), "kamera;", sum(1 for e in h.out if "maxspeed" in e["tags"]), "sınırlı;", sum(1 for e in h.out if "direction" in e["tags"]), "yönlü")
