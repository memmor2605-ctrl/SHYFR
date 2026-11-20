# SHYFR 2.0: migracja z 1.x

Zasada: **nie niszczymy danych 1.x i nie zmieniamy po cichu kluczy.** Archiwa źródłowe poprzednich etapów zostają jako punkt odniesienia.

| Dane 1.x | Czy migrują | Dlaczego / jak |
|---|---|---|
| Nazwa i profil | tak, jako dane wejściowe nowego konta | konto 2.0 zakłada się na serwerze; użytkownik wybiera nazwę (unikalność sprawdza serwer) |
| Tożsamości społecznościowe i klucze Nostr | tak | wynikają z klucza głównego; publiczny feed Nostr działa bez zmian |
| Historia rozmów | **tylko jako archiwum do odczytu** | zapisana w lokalnej bazie; odczyt offline możliwy, ale nie da się jej „wpisać" do nowych sesji |
| Sesje Double Ratchet | **nie** | nowy protokół zaczyna nowe sesje; przenoszenie stanu między protokołami byłoby niebezpieczne |
| Kontakty (adresy onion) | **nie** wprost | adresy onion znikają; kontakt dodajesz ponownie po nazwie użytkownika |
| Klucz tożsamości szyfrowania 1.x | **nie** | zastępowany nowym kluczem urządzenia; **rozmówcy muszą zobaczyć ostrzeżenie o zmianie klucza** |
| Ustawienia lokalne | część | motyw, rozmiar czcionki, powiadomienia |
| Załączniki | jako część archiwum | tylko do odczytu |

## Plan bezpiecznej ścieżki
1. Wersja przejściowa zawiera oba stosy: 1.x działa jak dotąd, a „Nowe konto SHYFR 2.0" jest opcją obok.
2. Eksport archiwum rozmów (czytelny, zaszyfrowany hasłem) przed jakimkolwiek usunięciem czegokolwiek.
3. Dopiero po okresie równoległym, i tylko za zgodą użytkownika, wygaszanie Tora i przekaźnika.
4. Protokół wiadomości: wybór i przegląd (patrz SECURITY) **przed** pierwszym wdrożeniem 2.0.

## Czego nie obiecuję
Ciągłości rozmów po przejściu na 2.0. To zmiana protokołu, a stare sesje nie przenoszą się bezpiecznie. Kopie 1.x (plik kopii zapasowej) zostają jedyną pełną kopią historii.
