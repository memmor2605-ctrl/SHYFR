#!/usr/bin/env python3
"""Podsumowanie wyników Android Lint: liczy Fatal/Error/Warning i wypisuje adnotacje GitHub dla Fatal/Error. Kod wyjścia 1, gdy są Fatal/Error."""
import sys, xml.etree.ElementTree as ET
path = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/lint-results-debug.xml"
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as e:
    print(f"::error::Brak lub uszkodzony raport lint ({path}): {e}"); sys.exit(2)
counts = {}
for i in root.iter("issue"):
    sev = i.get("severity", "?"); counts[sev] = counts.get(sev, 0) + 1
    if sev in ("Fatal", "Error"):
        loc = i.find("location")
        f = loc.get("file", "") if loc is not None else ""; l = loc.get("line", "1") if loc is not None else "1"
        print(f"::error file={f},line={l}::[{i.get('id')}] {i.get('message')}")
print("lint:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "brak uwag")
sys.exit(1 if counts.get("Fatal", 0) + counts.get("Error", 0) else 0)
