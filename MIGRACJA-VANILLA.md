# Jedna skrzynka: przeniesienie vanilla do VariableEnderChests

## Minecraft 26.3

Wersja 1.11.9.6 zawiera NBT-API 2.16.1 z obsługą Minecraft 26.3.
Migrację i odbiór nadmiaru sprawdzono na Paper 26.3 build 223 i Java 25:
192 sprawdzenia, w tym odczyt starej bazy i migracja danych vanilla z 1.21.11, shulkery,
nowy przedmiot z 26.3, odbiór nadmiaru oraz wspólna skrzynka dla bloku i komendy.
Serwer Paper 26.3 wymaga Java 25.

Podmień JAR przy wyłączonym serwerze. Zachowaj konfigurację i `data.db`.

Od 1.11.9.6 konwerter obsługuje współdzielony folder `<świat>/players/data`
używany przez Minecraft 26.1 i nowsze. Uwzględnia także sytuację, w której Bukkit
zwraca folder wymiaru `<świat>/dimensions/minecraft/overworld`. Dla starszych wersji
obsługiwany jest dawny folder `<świat>/playerdata`. Wybrana ścieżka jest wypisywana
w konsoli jako `Vanilla source folder`. Jeśli nowy folder istnieje, ma pierwszeństwo
przed starymi kopiami danych.

Wersje 1.11.9.2–1.11.9.5 nie rozpoznawały poprawnie nowego układu folderów.
Do migracji na 26.3 użyj wersji 1.11.9.6 lub nowszej.

## Gdy obie skrzynki są pełne

| Skrzynka pluginowa | Vanilla | Zachowane po migracji |
| --- | --- | --- |
| 27 pełnych slotów | 27 pełnych slotów | 54 stosy: 27 widocznych i 27 do odbioru |
| 54 pełne sloty | 27 pełnych slotów | 81 stosów: 54 widoczne i 27 do odbioru |

Nadmiar zostaje w bazie, a właściciel odbiera go przez `/retrieveender`,
również bez dodatkowej permisji. Dotyczy to także nadmiaru mieszczącego się
w 54 slotach zapisu, ale przekraczającego aktualną pojemność widocznej skrzynki.
Istniejące przedmioty nie są nadpisywane ani wyrzucane na ziemię.

## Zabezpieczenia przed kopiowaniem w 1.11.9.4

- Opóźniony odczyt nie zastępuje skrzynki, z której gracz już korzysta.
  Wywołania API gracza współdzielą odczyt w tle i zwracają `null` do jego zakończenia.
- Otwarta skrzynka lub okno odbioru nadmiaru zatrzymuje tę samą instancję w pamięci.
- Skrzynka offline jest usuwana z pamięci dopiero po udanym zapisie i tylko wtedy,
  gdy od wykonania kopii do zapisu nie zmieniono jej zawartości ani rozmiaru.
  Błąd zapisu pozostawia ją w pamięci. Kopie do zapisu nie współdzielą mutowalnych itemów.
- Końcowy zapis przy wyłączeniu czeka na trwający zapis okresowy, aby starsze dane
  nie nadpisały nowszych. Oczekujące odczyty powstrzymują usuwanie z pamięci.
- Zarówno `VanillaConverter`, jak i `VanillaMergeConverter` przenoszą przedmioty
  do pluginu i czyszczą vanilla po zatwierdzeniu transakcji. `overwrite` jest odrzucane.

## Wspólny magazyn

Ta wersja dodaje `single-enderchest: true` (domyślnie). Blok enderchesta,
komenda pluginu i próby otwarcia vanilla przez inne pluginy prowadzą do magazynu
VariableEnderChests. W tym trybie lista `disabled-worlds` jest ignorowana.

## Przeniesienie istniejących przedmiotów

1. Zatrzymaj serwer i podmień JAR na tę wersję. Zachowaj folder pluginu i bazę.
2. Zabezpiecz wejścia na czas migracji, np. trybem konserwacji. Uruchom serwer.
   Nikt nie może wejść przed migracją; samo wyjście graczy po zalogowaniu nie wystarcza.
3. W konsoli wpisz `enderchestconvert VanillaMergeConverter` i powtórz tę samą
   komendę w ciągu 5 sekund.
4. Zaczekaj na `Migration Complete!`, a następnie zrestartuj serwer i otwórz wejścia.

Nowy konwerter zachowuje dotychczasowe przedmioty pluginowe, dodaje WSZYSTKIE
przedmioty vanilla i po zatwierdzeniu zapisu w bazie usuwa `EnderItems` z plików
`players/data/*.dat` głównego zapisu świata (na starszych wersjach `playerdata/*.dat`).
To przeniesienie, a nie kopia. Identyczne
przedmioty z obu magazynów też są zachowywane; nie próbuje zgadywać, które są duplikatami.

Nadmiar ponad pojemność skrzynki zostaje w tej samej bazie pluginu. Odbiera się go
przez `/retrieveender` (właściciel nadmiaru migracyjnego może go odebrać bez
dodatkowego uprawnienia). Jeśli widoczny odbiór się zapełni, należy zabrać itemy
i ponownie otworzyć `/retrieveender`, aby zobaczyć kolejne. Nie są wyrzucane na ziemię.

Każdy zmieniany plik gracza oraz poprzednia surowa zawartość pluginowa są kopiowane
do `plugins/VariableEnderChests/backups/vanilla-move-.../`. Baza ma także dziennik
transakcji `vec_vanilla_moves`: po przerwaniu między zapisem w bazie a wyczyszczeniem
vanilla ponowienie kończy przeniesienie bez ponownego dodawania itemów.

Jeżeli migracja zgłosi błąd, pozostaw wejścia zablokowane, sprawdź błąd w konsoli,
zrestartuj i ponów NOWY konwerter. Nie przywracaj samych plików graczy lub samej
bazy z różnych momentów — cofnięcie musi obejmować obie strony i dziennik migracji.

W oryginalnej wersji autora `VanillaConverter overwrite` nadpisuje magazyn pluginu;
w tej wersji flaga jest odrzucana i nie trzeba jej podawać.
Powrót do starszej wersji pluginu przed odebraniem nadmiaru ponad 54 sloty nie jest
bezpieczny, ponieważ starsza wersja obcinała dłuższe magazyny.

Przenoszone są pliki standardowego `playerdata` pierwszego świata serwera.
Osobne magazyny zarządzane przez pluginy ekwipunków wielu światów nie są objęte
tym konwerterem.
