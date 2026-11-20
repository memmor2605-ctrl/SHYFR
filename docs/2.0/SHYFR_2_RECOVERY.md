# SHYFR 2.0: odzyskiwanie i usuwanie konta (etap 41)

## Model
Sekret uwierzytelnienia = Argon2id(login, hasło) + HKDF, liczony na urządzeniu; serwer widzi tylko jego hasz (scrypt). Klucze E2EE (tożsamość, prekeye, sesje) żyją wyłącznie na urządzeniu w pliku zaszyfrowanym kluczem Android Keystore.

## Co da się odzyskać
| Sytuacja | Wynik |
|---|---|
| Hasło znane, nowy telefon | Logowanie rejestruje nowe urządzenie z nowymi kluczami. Nowe wiadomości działają; historia i sesje starego telefonu NIE wracają. Rozmówcy zobaczą zmianę numeru bezpieczeństwa. |
| Zgubiony telefon | Zalogować się gdzie indziej i odwołać urządzenie (DELETE /v1/devices/{id}): sesje, klucze i oczekujące wiadomości tego urządzenia są unieważniane na serwerze. |
| Hasło zapomniane | Brak odzyskania: serwer nie zna hasła ani kluczy. Konto jest nieodwracalnie niedostępne. |

Nie ma kopii zapasowej kluczy ani frazy odzyskiwania. Nie obiecujemy odzyskania kluczy prywatnych. Jeśli taki mechanizm powstanie, wymaga osobnego projektu (np. fraza zapasowa trzymana przez użytkownika) i przeglądu.

## Usuwanie konta (ekran „Usuń konto”)
Ponowne uwierzytelnienie hasłem + wpisanie „USUŃ” -> `DELETE /v1/accounts/me` (istniejący endpoint, jedna transakcja, limit 5/h) -> kasowanie lokalnej historii, kolejek, kluczy E2EE i sesji, anulowanie zadań WorkManager. Przy błędnym haśle (403) nic nie jest usuwane. Przed usunięciem token push urządzenia jest wyrejestrowany. Backend usuwa też `push_tokens` (test).
