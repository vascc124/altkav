#!/bin/sh
# Fetch the Israeli MOT GTFS feed into .cache/ (~180 MiB, rebuilt nightly).
set -e
cd "$(dirname "$0")/.."
mkdir -p .cache
curl -sSL --retry 3 -o .cache/israel-gtfs.zip \
  https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip
ls -l .cache/israel-gtfs.zip
# AltKav+: the Na'im BaSofash weekend lines, published by the Tel Aviv municipality outside the MOT feed.
curl -sSL --retry 3 -o .cache/naim-gtfs.zip \
  https://opendatasource.tel-aviv.gov.il/OpenData_Ducaments/gtfs.zip || echo "Na'im GTFS not fetched"
ls -l .cache/naim-gtfs.zip 2>/dev/null || true
