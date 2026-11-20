#!/usr/bin/env python3
"""Podsumowanie raportów JUnit z Gradle. Kod wyjścia 1, gdy są niepowodzenia/błędy ALBO nie uruchomiono żadnego testu (pusty zbiór to nie sukces)."""
import glob, sys, xml.etree.ElementTree as ET
pattern = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest/*.xml"
t = f = e = s = 0
for p in glob.glob(pattern):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print(f"testy Kotlina: {t} uruchomionych, {f} niepowodzeń, {e} błędów, {s} pominiętych (pominięte = m.in. testy integracyjne bez SHYFR2_TEST_URL)")
sys.exit(1 if (t == 0 or f or e) else 0)
