"""EGM'nin EDS haritası sayfasındaki (onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx) gömülü
`var markers = [...]` listesini uygulamanın okuduğu JSON'a çevirir. Ortalama hız koridoru (OHTS) uçları
atlanır: koridorlar bakanlıktan tüm güzergahıyla geliyor.
Kullanım: python3 -I egm_eds.py sayfa.html cikti.json"""
import json, re, sys, time

def parse(html):
    a = html.find("var markers"); b = html.find("];", a)
    items = re.findall(r'"Aciklama":\s*\'(.*?)\',\s*"lat":\s*\'([^\']*)\',\s*"lng":\s*\'([^\']*)\'', html[a:b], re.S)
    out = []
    for desc, lat, lng in items:
        d = " ".join(desc.split())
        if "OHTS" in d.upper():
            continue
        try:
            la, lo = float(lat.replace(",", ".")), float(lng.replace(",", "."))
        except ValueError:
            continue
        if not (35.5 <= la <= 42.5 and 25.5 <= lo <= 45.0):
            continue
        out.append({"a": d, "lat": round(la, 6), "lng": round(lo, 6)})
    return out

if __name__ == "__main__":
    pts = parse(open(sys.argv[1], encoding="utf-8", errors="replace").read())
    doc = {"generated": time.strftime("%Y-%m-%d"), "source": "EGM EDS haritası (onlineislemler.egm.gov.tr)", "points": pts}
    json.dump(doc, open(sys.argv[2], "w"), ensure_ascii=False, separators=(",", ":"))
    print(len(pts), "EDS noktası")
