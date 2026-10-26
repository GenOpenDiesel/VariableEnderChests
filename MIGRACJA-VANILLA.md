# Jedna skrzynka: przeniesienie vanilla do VariableEnderChests

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
`playerdata/*.dat` głównego świata. To przeniesienie, a nie kopia. Identyczne
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

Nie używaj starego `VanillaConverter overwrite` do scalania: nadpisuje magazyn pluginu.
Powrót do starszej wersji pluginu przed odebraniem nadmiaru ponad 54 sloty nie jest
bezpieczny, ponieważ starsza wersja obcinała dłuższe magazyny.

Przenoszone są pliki standardowego `playerdata` pierwszego świata serwera.
Osobne magazyny zarządzane przez pluginy ekwipunków wielu światów nie są objęte
tym konwerterem.
