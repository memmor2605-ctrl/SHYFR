# System projektowy SHYFR (opis stanu w kodzie)

To opis tokenów i komponentów faktycznie używanych w aplikacji (`ui/Components.kt`, `ui/ShyfrApp.kt`, `ui/PublicUi.kt`). Nie jest to osobna biblioteka; zmiany robi się w tych plikach.

## Kolory (ciemny motyw jest główny, jasny dopasowuje się do systemu)
| Rola | Ciemny | Jasny |
|---|---|---|
| Tło (`background`, `ShInk`) | #0E0C0A | #F8F2E9 |
| Powierzchnia (`ShSurface`) | #1B1713 | #FFFFFF |
| Powierzchnia wyższa (`ShSurfaceHi`) | #2D261E | #EFE6D8 |
| Akcent (`primary`) | #F6E3C3 (krem) | #7A4E17 |
| Tekst na akcencie | #22180A | #FFFFFF |
| Tekst główny | #F4EEE6 | #221B12 |
| Tekst wyciszony (`ShMuted`) | #A49A8C | #7A6D5C |
| Kontur / dzielniki | #5A5043 | #CFC3B0 |
| Błąd | #E57373 | #BA1A1A |
| Poświata nagłówka (`warmGlow`) | #4A3A28 → tło | #EBD9BC → tło |
Stan „adres się rozgłasza”: #E0B35A, „online”: #6FCF97.

## Typografia
Czcionka systemowa. Rozmiary: 11 sp (metadane), 12 sp (podpisy, ostrzeżenia), 13–15 sp (treść), 16 sp (tytuły kart), 20 sp (tytuły stanów pustych). Wagi: Normal, Medium (nazwy), SemiBold (nagłówki sekcji).

## Odstępy i promienie
Odstępy: 2, 4, 6, 8, 10, 12, 14, 16 dp (siatka co 2 dp; karty mają 14 dp wypełnienia, ekrany 12–16 dp po bokach).
Promienie: 12–14 dp (małe panele), 18–20 dp (wiersze), 24 dp (karty), 36 dp (pływająca nawigacja), 50% (pigułki, przyciski okrągłe).

## Komponenty
- **Pływająca nawigacja** (`FloatingNav`): 5 okrągłych przycisków 44 dp + „+”; wybrany ma kolor akcentu; kropka przy Aktywności.
- **Zakładki-pigułki** (`PillTab`): wybrana w kolorze akcentu, pozostałe przyciemnione.
- **Karty** (`PostCard`, wiersze społeczności, ankiety): tło `ShSurface` z przezroczystością 85%, promień 24 dp, bez ramek.
- **Pola tekstowe**: wyszukiwarka wypełniona bez wskaźnika (28 dp), formularze `OutlinedTextField`.
- **Dialogi**: `AlertDialog` Material 3 (tytuł, treść przewijana, maks. wysokość 460–480 dp).
- **Menu**: `DropdownMenu` (akcje wpisu, repost, reakcje).
- **Stany**: ładowanie = `CircularProgressIndicator`; błąd i pusty ekran = `EmptyState` (tytuł, opis, przycisk akcji „Ponów” lub „Otwórz ustawienia”).
- **Ikony**: zestaw `Ic` (wektory Material), rozmiary 16/20/22/24 dp.

## Animacje
Domyślne animacje Compose (listy, dialogi). Brak własnych przejść; to świadome ograniczenie dla szybkości działania.

## Dostępność: znane braki
- Nawigacja główna, przyciski paska górnego i menu wpisu mają opisy dla TalkBack (`Modifier.a11y`); pozostałe przyciski ikonowe (czat, ustawienia) jeszcze nie.
- Nie wykonano testu TalkBack ani audytu kontrastu (akcent #F6E3C3 na #0E0C0A jest wysoki, ale kolory pomocnicze nie były mierzone).
- Rozmiary dotykowe: przyciski ikonowe 32–48 dp; menu wpisu ma 40 dp, nawigacja 44 dp, więc nadal poniżej zalecanych 48 dp.
