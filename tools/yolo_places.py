"""Places worth a trip, for AltKav+'s YOLO modes: nature reserves, national parks, parks, beaches, springs,
waterfalls, viewpoints, museums and attractions across Israel, from OpenStreetMap through one Overpass query.

Writes android/app/src/main/assets/yolo_places.json: a compact list of [name_he, name_en, kind, lat, lon, size],
size being a rough area in hectares for areas (0 for points). OSM data (c) OpenStreetMap contributors, ODbL.

    python tools/yolo_places.py
"""
import json, math, os, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "android", "app", "src", "main", "assets", "yolo_places.json")
# The main instance and its public mirrors, tried in turn when one is busy.
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
BBOX = "29.45,34.2,33.35,35.95"  # Israel (south, west, north, east)
# Asked in three bands, so no one query is heavy enough to time out on a busy public server.
BANDS = ["29.45,34.2,31.2,35.95", "31.2,34.2,32.3,35.95", "32.3,34.2,33.35,35.95"]

# One query per kind: together they time out on the public server.
SELECTORS = [
    'nwr["leisure"="nature_reserve"]["name"]', 'nwr["boundary"="national_park"]["name"]',
    'nwr["boundary"="protected_area"]["name"]', 'wr["leisure"="park"]["name"]', 'nwr["natural"="beach"]["name"]',
    'nwr["natural"="spring"]["name"]', 'nwr["waterway"="waterfall"]["name"]', 'nwr["tourism"="viewpoint"]["name"]',
    'nwr["tourism"="museum"]["name"]', 'nwr["tourism"="attraction"]["name"]', 'nwr["tourism"="zoo"]["name"]',
    'wr["landuse"="forest"]["name"]',
]


def query(sel, box):
    return f"[out:json][timeout:120];({sel}({box}););out tags bb;"


def kind(t):
    if t.get("leisure") == "nature_reserve" or t.get("boundary") in ("national_park", "protected_area"):
        return "reserve"
    if t.get("natural") == "beach": return "beach"
    if t.get("natural") == "spring" or t.get("waterway") == "waterfall": return "water"
    if t.get("landuse") == "forest": return "forest"
    if t.get("leisure") == "park": return "park"
    if t.get("tourism") == "viewpoint": return "view"
    if t.get("tourism") == "museum": return "museum"
    if t.get("tourism") == "zoo": return "zoo"
    return "attraction"


def hectares(b):
    if not b: return 0
    dy = (b["maxlat"] - b["minlat"]) * 111.2
    dx = (b["maxlon"] - b["minlon"]) * 111.2 * math.cos(math.radians(b["minlat"]))
    return round(dx * dy * 100 * 0.6)  # a bounding box overstates the shape; roughly 60% of it


def main():
    elements = []
    for sel, box in [(sel, box) for sel in SELECTORS for box in BANDS]:
        d = None
        for attempt in range(12):
            url = OVERPASS[attempt % len(OVERPASS)]
            try:
                req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": query(sel, box)}).encode(),
                                             headers={"User-Agent": "AltKavPlus (github.com/vascc124/altkav)"})
                d = json.load(urllib.request.urlopen(req, timeout=240))
                if "elements" in d and not d.get("remark", "").startswith("runtime error"): break
            except Exception as ex:
                print(f"  {url.split('/')[2]} busy ({ex}); trying the next")
            time.sleep(20)
        d = d or {}
        print(f"  {sel} [{box}]: {len(d.get('elements', []))}", d.get("remark", ""), flush=True)
        elements += d.get("elements", [])
        time.sleep(3)  # one query at a time, with a pause: a shared public server
    out, seen = [], set()
    for e in elements:
        t = e.get("tags", {})
        b = e.get("bounds")
        # Areas come with their bounding box; their middle stands for them.
        c = ({"lat": e["lat"], "lon": e["lon"]} if "lat" in e else
             {"lat": (b["minlat"] + b["maxlat"]) / 2, "lon": (b["minlon"] + b["maxlon"]) / 2} if b else None)
        if not c: continue
        k = kind(t)
        size = hectares(e.get("bounds"))
        # Pocket parks and tiny groves aren't trips.
        if k in ("park", "forest") and size < 3: continue
        he = t.get("name:he") or t.get("name", "")
        en = t.get("name:en") or ""
        key = (he or en, k, round(c["lat"], 3), round(c["lon"], 3))
        if key in seen: continue
        seen.add(key)
        out.append([he, en, k, round(c["lat"], 5), round(c["lon"], 5), size])
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    counts = {}
    for p in out: counts[p[2]] = counts.get(p[2], 0) + 1
    print(len(out), "places", counts, "->", OUT, os.path.getsize(OUT) // 1024, "KB")


if __name__ == "__main__":
    main()
