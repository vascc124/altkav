"""Collect the Na'im BaSofash weekend lines (Tel Aviv area, run by the municipalities, not in the MOT feed).

Stops and their order come from busofash.co.il's line pages; route shapes, colours and stop positions from
the Tel Aviv municipality's public GIS layers (IView2/MapServer 992 lines, 993 stops). Writes tools/naim.json.
Times are not published there; tools/naim_times.py adds them.

    python tools/naim_fetch.py
"""
import html, json, os, re, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = "https://busofash.co.il/"
GIS = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer/"
UA = {"User-Agent": "AltKavPlus timetable builder (github.com/vascc124/altkav)"}


def get(url):
    time.sleep(1.0)  # one page a second: a small municipal site
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode("utf-8")


def gis(layer, where="1=1"):
    q = urllib.parse.urlencode({"where": where, "outFields": "*", "returnGeometry": "true", "outSR": 4326, "f": "json"})
    return json.loads(get(GIS + f"{layer}/query?{q}"))["features"]


def text(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s)).strip()


def line_page(url):
    s = get(url)
    m = re.search(r"<title>\s*([^<]*)</title>", s)
    title = text(m.group(1)) if m else ""
    num = re.search(r"קו\s*([^\s-]+)", title)
    dirs = []
    for wrap in re.findall(r'<div class="line-page-stations-list-wrap[^"]*">(.*?)</ul>', s, flags=re.S):
        ends = [text(x) for x in re.findall(r"<div>(.*?)</div>", wrap.split("</h2>")[0], flags=re.S)]
        stops = [
            {"code": int(c) if c.strip().isdigit() else None, "name": text(n), "city": text(ci)}
            for c, n, ci in re.findall(
                r'lines-list-sku">(.*?)</div><div class="lines-list-name">(.*?)</div><div class="lines-list-municipality">(.*?)</div>',
                wrap, flags=re.S)
        ]
        if stops:
            dirs.append({"from": ends[0] if ends else "", "to": ends[-1] if ends else "", "stops": stops})
    return {"line": num.group(1) if num else title, "url": url, "directions": dirs}


def main():
    home = get(SITE)
    urls = sorted(set(html.unescape(u) for u in re.findall(r'href="(https://busofash\.co\.il/\?lines=[^"]+)"', home)))
    lines = []
    for u in urls:
        p = line_page(u)
        print(f"{p['line']:>8}  {len(p['directions'])} directions  " + ", ".join(str(len(d['stops'])) for d in p["directions"]))
        lines.append(p)

    shapes, colors = {}, {}
    for f in gis(992):
        a = f["attributes"]
        n = str(a.get("Line"))
        paths = f.get("geometry", {}).get("paths", [])
        shapes.setdefault(n, []).extend([[round(y, 6), round(x, 6)] for x, y in path] for path in paths)
        if a.get("route_color"):
            colors[n] = a["route_color"]
    where = {}
    for f in gis(993):
        a = f["attributes"]
        if a.get("stop_code"):
            where[a["stop_code"]] = [round(f["geometry"]["y"], 6), round(f["geometry"]["x"], 6)]

    for p in lines:
        p["color"] = colors.get(p["line"])
        p["shape"] = shapes.get(p["line"], [])
        for d in p["directions"]:
            for st in d["stops"]:
                st["at"] = where.get(st["code"])
    out = {"source": [SITE, GIS + "992", GIS + "993"], "fetched": time.strftime("%Y-%m-%d"),
           "hours": "Fri 16:30-02:00, Sat 09:00-17:30, free", "lines": lines}
    json.dump(out, open(os.path.join(HERE, "naim.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    missing = sum(1 for p in lines for d in p["directions"] for s in d["stops"] if not s["at"])
    print(f"{len(lines)} lines, {missing} stops without a GIS position -> tools/naim.json")


if __name__ == "__main__":
    main()
