# Stan bezpieczeństwa SHYFR 2.0

To jest samoocena autora kodu, **NIE niezależny audyt**.

## Szyfrowanie wiadomości (E2EE)
Wdrożone w kodzie na bazie libsignal (PQXDH + Double Ratchet, AGPL-3.0); własnej kryptografii nie ma. Treść jest szyfrowana przed wysłaniem i odszyfrowywana na urządzeniu odbiorcy; backend dostaje wyłącznie szyfrogram. Klucze prywatne i sesje leżą w pliku zaszyfrowanym kluczem Android Keystore (StrongBox, gdy dostępny) i nigdy nie opuszczają urządzenia; na serwer trafiają klucze publiczne. Bez działającego szyfru aplikacja odmawia wysyłania (nie wysyła jawnego tekstu).

**Jeszcze niezweryfikowane:** kod E2EE nie był kompilowany ani testowany między dwoma urządzeniami. Do czasu pierwszego udanego builda w CI i testu dwóch telefonów nie traktuj go jako sprawdzonego.

## Zweryfikowane testami (backend)
Ochrona puli kluczy jednorazowych i KEM; logowanie bez blokowania cudzych kont i bez wyliczania użytkowników; atomowe usuwanie konta; zaufanie do proxy; limity żądań; IDOR na wiadomościach; ACK/idempotencja; tokeny hashowane w bazie; odświeżanie z wykrywaniem ponownego użycia; backup/restore z weryfikacją.

## Model zaufania do kluczy
TOFU: pierwszy klucz rozmówcy jest zaufany; zmiana klucza blokuje wysyłanie i odbiór, dopóki użytkownik jawnie nie zaufa nowemu kluczowi (ekran „Weryfikacja": numer bezpieczeństwa + „Zaufaj nowemu kluczowi"). Zmiana konta na tym samym telefonie czyści klucze, sesje i rozmowy poprzedniego konta.

## Znane słabości
Metadane (kto z kim i kiedy) widzi serwer; brak rotacji podpisanego prekeya; numer bezpieczeństwa liczony dla pierwszego znanego urządzenia rozmówcy; utrata klucza Keystore wymaga ponownej rejestracji urządzenia (aplikacja nie generuje po cichu nowej tożsamości); utrata odpowiedzi na refresh kończy sesję; globalny licznik błędnych logowań podatny na rozproszony DoS; brak przypinania certyfikatu; brak audytu zewnętrznego.
