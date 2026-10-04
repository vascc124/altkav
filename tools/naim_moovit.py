"""Na'im BaSofash weekend timetables, read from Moovit's public web line pages.

The municipalities publish routes and stops (tools/naim_fetch.py) but not times; Moovit's web line page
embeds the day's full schedule for both directions (appState.line.lineDirectionsFullSchedule). This opens
each Tel Aviv-Yafo Municipality 7xx line for a Friday and a Saturday in the local Firefox helper
(tools/ktuvit-browser, port 9333), one page at a time at a human pace, and writes tools/naim_times.json:
per direction, the stops (MOT codes, names, positions) and, per weekday, first-stop departures in seconds
after that day's midnight plus each stop's typical offset from the first.

    python tools/naim_moovit.py
"""
import datetime, json, os, re, statistics, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = "http://127.0.0.1:9333"
# Municipal lines the MOT feed doesn't carry: (Moovit agency id, operator name in the bundle, line filter, days).
AGENCIES = [
    (2910830, 'נעים בסופ"ש', r"7\d\d", ["fri", "sat"]),            # Tel Aviv-Yafo: the Na'im BaSofash lines
    (3412685, "עיריית רמת גן", r"[^/]+?", ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]),  # Savbus
    # Holon (3799531) is left out: its Sokolov shuttle still shows on Moovit but hasn't run for years.
]
WEEKDAY = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}
TZ = datetime.timezone(datetime.timedelta(hours=3))  # Israel summer time; offsets are kept relative anyway


def helper(path, data=None):
    req = urllib.request.Request(HELPER + path, data=data.encode() if data else None, method="POST" if data else "GET")
    return json.loads(urllib.request.urlopen(req, timeout=90).read().decode("utf-8"))


def goto(url):
    helper("/goto?" + urllib.parse.urlencode({"url": url}))
    time.sleep(9)  # let it render, and stay at a human pace


def js(code):
    return helper("/eval", code)["result"]


def state():
    raw = js('(()=>{const s=document.querySelector("script#serverApp-state"); return s ? s.textContent : ""})()')
    return json.loads(raw) if raw else None


def line_groups(agency, pattern):
    goto(f"https://moovitapp.com/index/en/public_transit-lines-Israel-1-{agency}")
    links = js('[...new Set([...document.querySelectorAll("a")].map(a=>a.href))].join(" ")')
    names = {}
    for h in links.split(" "):
        m = re.search(rf"line-({pattern})-Israel-1-{agency}-(\d+)-0$", urllib.parse.unquote(h))
        if m:
            name, g = m.group(1).replace("_", " "), int(m.group(2))
            name = {"blue savbus": "סבבוס כחול", "green savbus": "סבבוס ירוק"}.get(name, name)  # English-only slugs
            # The index links each line under its English and its Hebrew slug; keep the Hebrew one.
            if g not in names or re.search("[֐-׿]", name):
                names[g] = name
    return sorted(((n, g) for g, n in names.items()), key=lambda x: x[1])


def day_offset(d):
    return (d - datetime.date(1970, 1, 1)).days


def next_weekday(wd):  # Monday=0 .. Sunday=6
    today = datetime.date.today()
    return today + datetime.timedelta((wd - today.weekday()) % 7 or 7)


def offsets(arrivals, stops):
    """First-stop departures, and per stop the median time after them. Each bus is followed stop by stop:
    at a stop it is the first arrival from its time at the previous stop on (taking the first arrival
    after the departure alone would pick the bus ahead of it once the route is longer than the headway)."""
    lists = [sorted(a["arrivalTime"] for a in arrivals.get(str(s["id"]), [])) for s in stops]
    first = lists[0]
    per_stop = [[] for _ in stops]
    for t0 in first:
        cur = t0
        for k, times in enumerate(lists):
            nxt = next((t for t in times if cur <= t <= cur + 1800), None)
            if nxt is None:
                continue
            per_stop[k].append(nxt - t0)
            cur = nxt
    return first, [int(statistics.median(v)) if v else None for v in per_stop]


def main():
    directions = {}
    for agency, agency_name, pattern, day_keys in AGENCIES:
      groups = line_groups(agency, pattern)
      print(agency_name, len(groups), "Moovit line groups:", " ".join(f"{n}/{g}" for n, g in groups))
      days = {k: next_weekday(WEEKDAY[k]) for k in day_keys}
      for n, g in groups:
        for key, d in days.items():
            goto(f"https://moovitapp.com/tripplan/israel-1/lines/{urllib.parse.quote(n)}/{g}/he?dayOffset={day_offset(d)}")
            st = state()
            if not st:
                print("  no state for", n, g, key, "- stopping"); sys.exit(1)
            midnight = int(datetime.datetime.combine(d, datetime.time(), TZ).timestamp())
            for dr in st["appState"]["line"].get("lineDirectionsFullSchedule", []):
                for opt in dr.get("lineOptions", []):
                    stops = opt.get("stops", [])
                    if not stops:
                        continue
                    first, offs = offsets(opt.get("stopArrivals", {}), stops)
                    k = f"{n}:{opt['lineId']}"
                    e = directions.setdefault(k, {
                        "line": n, "group": g, "agency": agency_name, "moovitLine": opt["lineId"], "title": dr.get("lineTitle", ""),
                        "stops": [{"code": int(s["stopCode"]) if str(s.get("stopCode", "")).isdigit() else None,
                                   "name": s.get("name", ""),
                                   "at": [s["location"].get("latitude", 0) / 1e6, s["location"].get("longitude", 0) / 1e6]
                                   if isinstance(s.get("location"), dict) else None} for s in stops],
                        "days": {},
                    })
                    e["days"][key] = {"departures": [t - midnight for t in first], "offsets": offs,
                                      "arrivals": {str(i): sorted(a["arrivalTime"] - midnight for a in opt["stopArrivals"].get(str(st["id"]), []))
                                                   for i, st in enumerate(stops)}}
                    print(f"  {n:>4} {key} {dr.get('lineTitle','')[:40]:<40} {len(stops):>3} stops {len(first):>3} departures")
    out = {"source": "moovitapp.com web line pages", "fetched": datetime.date.today().isoformat(),
           "directions": list(directions.values())}
    json.dump(out, open(os.path.join(HERE, "naim_times.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(len(directions), "directions -> tools/naim_times.json")


if __name__ == "__main__":
    main()
