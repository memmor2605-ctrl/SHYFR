#!/usr/bin/env bash
# Generuje Gradle Wrapper (gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar) dla wersji 8.13. Wymaga zainstalowanego Gradle 8.13 i dostępu do sieci.
# Wynik (w tym gradle-wrapper.jar) TRZEBA zatwierdzić w repozytorium: wtedy build jest powtarzalny, a CI nie generuje wrappera na bieżąco.
# Uwaga: pliku .jar nie dało się wygenerować w środowisku etapu 39 (brak Gradle i sieci), dlatego go tu nie ma.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle wrapper --gradle-version 8.13 --distribution-type bin
echo "Gotowe. Sprawdź: ./gradlew --version, potem: git add gradlew gradlew.bat gradle/wrapper && git commit"
