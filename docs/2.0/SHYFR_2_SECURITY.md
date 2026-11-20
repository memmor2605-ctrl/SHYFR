# SHYFR 2.0: model bezpieczeństwa

Opis tego, co jest zaimplementowane i sprawdzone testami serwera, oraz tego, co wymaga decyzji i **niezależnego audytu**. Testy jednostkowe i przegląd kodu nie są audytem bezpieczeństwa.

## Uwierzytelnianie (zaimplementowane, 35 testów serwera zielonych)
- Hasło **nie opuszcza urządzenia**: klient wyprowadza z niego sekret uwierzytelnienia (docelowo Argon2id, który aplikacja już ma), a serwer przechowuje tylko `scrypt(sekret, sól)`. Testy sprawdzają, że sekret nie trafia do bazy.
- Wewnętrzny identyfikator konta jest stały i niezależny od publicznej nazwy użytkownika.
- Tokeny dostępu (15 min) i odświeżania (30 dni) są losowe, w bazie leżą tylko ich skróty SHA-256. Odświeżanie rotuje token, a **ponowne użycie starego tokenu unieważnia całą sesję** (wykrycie kradzieży).
- Blokada po 5 błędnych próbach (rosnąca do 15 min), jednakowa odpowiedź dla nieznanego konta i złego sekretu, wyrównanie czasu.
- Ograniczenia liczby żądań: rejestracja, logowanie, wysyłka, pobieranie kluczy. Obecnie w pamięci jednego procesu.
- Unieważnienie urządzenia kończy jego sesje, usuwa jego klucze i kolejkę.

## Autoryzacja
Każdy punkt końcowy poza rejestracją, logowaniem i zdrowiem wymaga tokenu. Dostęp do obiektów jest sprawdzany po stronie serwera: tylko odbiorca może pobierać, potwierdzać i oznaczać jako przeczytaną swoją wiadomość; tylko nadawca widzi stan wysłanej. Testy BOLA (obca osoba, nadawca w roli odbiorcy) są zielone.

## Szyfrowanie end-to-end: **decyzja niepodjęta**
Serwer przechowuje i przekazuje nieprzezroczysty szyfrogram i nigdy nie potrzebuje treści. Ale **protokół po stronie klienta nie jest wybrany ani wdrożony w 2.0**. Stan faktyczny i ryzyka:
- Aplikacja 1.x ma własną implementację Double Ratchet, która **nie przeszła niezależnego przeglądu**. Nie wolno nazywać jej „poziomem Signal".
- Kandydat: biblioteka Signal (libsignal). Wymaga sprawdzenia przed decyzją: licencji (według mojej wiedzy AGPL-3.0, co nakłada obowiązki na całą aplikację; **zweryfikuj**), utrzymania, dostępności artefaktów na Androida i stabilności API. Tego nie dało się sprawdzić offline.
- Szyfrowanie **grup** nie wejdzie do pierwszego wydania, dopóki nie będzie oparte na sprawdzonym protokole i przetestowane. Zwykłych grup serwerowych nie będziemy nazywać szyfrowanymi.
- Uwierzytelnianie kontaktów (numery bezpieczeństwa) i ostrzeżenia o zmianie klucza: katalog kluczy je umożliwia, interfejsu nie ma.

## Co widzi serwer
Nazwy użytkowników, kto do kogo pisze, kiedy, rozmiary szyfrogramów, adresy IP w czasie połączenia (serwer ich nie zapisuje: logi dostępu są wyłączone, a za proxy należy to samo zapewnić), listę urządzeń, publiczne klucze, tokeny powiadomień (po wdrożeniu FCM). **Nie ma tu anonimowości sieciowej**: po usunięciu Tora operator i dostawca internetu widzą, że łączysz się z SHYFR.
Publiczne treści (feed, społeczności) **nie są szyfrowane** i może je przetwarzać moderacja.

## Znane luki (stan na ten etap)
Brak TLS w samym serwerze (proxy), ograniczenia żądań tylko w pamięci, brak kopii i odzyskiwania konta w 2.0, brak weryfikacji klientów (np. Play Integrity), brak monitoringu nadużyć, brak niezależnego audytu, brak testów penetracyjnych.

## Usuwanie konta
`DELETE /v1/accounts/me` (z potwierdzeniem sekretem) usuwa konto, urządzenia, sesje, klucze i wszystkie kolejki wiadomości z udziałem konta. Test sprawdza bazę. Nie obejmuje systemów, których SHYFR nie kontroluje (np. kopie wiadomości na telefonach rozmówców). Zewnętrzny adres żądania usunięcia dla Google Play: **do ustawienia przez właściciela**.
