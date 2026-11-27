# Sphere Sweeper

Saper na kuli. Plansza to sfera pokryta sześciokątami, a dokładnie 12 pól to pięciokąty.
Kulę obracasz palcem w dowolną stronę, a pola odkrywasz i oznaczasz flagami jak w klasycznym Saperze.

Siatka powstaje z dwudziestościanu foremnego (ikosaedru). Gra dzieli każdą ścianę na mniejsze trójkąty
z częstotliwością `n` i rzutuje wierzchołki na sferę. Każdy wierzchołek tej siatki staje się jednym polem.
Taki podział nazywa się wielościanem Goldberga. Liczba pól wynosi `10·n² + 2`.

## Sterowanie

| Gest | Działanie |
|------|-----------|
| Przeciągnięcie jednym palcem | Obraca kulę (z bezwładnością po puszczeniu) |
| Rozsunięcie lub zsunięcie dwóch palców | Przybliża lub oddala kulę |
| Pojedyncze dotknięcie zakrytego pola | Odkrywa pole (z opóźnieniem ok. 250 ms, żeby odróżnić je od podwójnego dotknięcia) |
| Podwójne dotknięcie zakrytego pola | Stawia lub zdejmuje flagę |
| Podwójne dotknięcie odkrytej liczby | Akord: gdy liczba flag wokół pola równa się liczbie na polu, odkrywa pozostałych sąsiadów |

W ustawieniach możesz włączyć tryb „flaga długim przytrzymaniem”.
Wtedy przytrzymanie pola przez ok. 400 ms stawia flagę, a pojedyncze dotknięcie odkrywa pole bez opóźnienia.
Akord nadal działa podwójnym dotknięciem odkrytej liczby.

Dotknięcie działa tylko na polach z przodu kuli. Ruch palca dłuższy niż próg przesunięcia to zawsze obrót, nigdy dotknięcie.

## Zasady

1. Liczba na odkrytym polu mówi, ile min leży na polach sąsiednich (5 albo 6 sąsiadów).
2. Miny trafiają na planszę dopiero przy pierwszym odkryciu. Pierwsze pole i wszyscy jego sąsiedzi są zawsze bezpieczni.
3. Odkrycie pola z zerem odkrywa cały spójny obszar zer i jego obramowanie z liczbami.
4. Wygrywasz, gdy odkryjesz wszystkie pola bez min.
5. Przegrywasz, gdy odkryjesz minę. Gra pokazuje wtedy wszystkie miny i błędnie postawione flagi.

Licznik u góry pokazuje liczbę min minus liczbę flag. Zegar startuje przy pierwszym odkryciu.
Gra zapisuje najlepszy czas dla każdego rozmiaru planszy.

## Rozmiary planszy

| Rozmiar | n | Pola | Miny |
|---------|---|------|------|
| Tiny | 2 | 42 | 6 |
| Small | 3 | 92 | 14 |
| Medium | 4 | 162 | 28 |
| Large | 5 | 252 | 45 |
| Huge | 6 | 362 | 65 |
| Giant | 8 | 642 | 125 |

Liczby min zmienisz w jednym miejscu: `app/src/main/java/com/example/spheresweeper/logic/GameConstants.kt`.

## Wersje narzędzi

| Narzędzie | Wersja |
|-----------|--------|
| Android Gradle Plugin (AGP) | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |
| Kompilator Compose (`kotlinCompilerExtensionVersion`) | 1.5.14 |
| Compose BOM | 2024.06.00 |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |

Wersje bibliotek są w pliku `gradle/libs.versions.toml` (katalog wersji Gradle).

## Budowanie lokalne

Potrzebujesz JDK 17 i Android SDK z platformą 34.
Ścieżkę do SDK podaj w zmiennej `ANDROID_HOME` albo w pliku `local.properties` (`sdk.dir=...`).

```bash
./gradlew testDebugUnitTest assembleDebug
```

Gotowy plik APK znajdziesz tutaj:

```
app/build/outputs/apk/debug/app-debug.apk
```

Instalacja na telefonie podłączonym przez USB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Wersję release zbudujesz poleceniem `./gradlew assembleRelease`.
Bez zmiennych podpisu (opis niżej) Gradle podpisze ją kluczem debug, więc budowanie zawsze się uda.

## Pobieranie APK z GitHub Actions

Każdy push, pull request i ręczne uruchomienie buduje APK w GitHub Actions.

1. Otwórz repozytorium na GitHubie i przejdź do zakładki **Actions**.
2. Wybierz workflow **Build** i kliknij ostatni udany przebieg (zielony znacznik).
3. Na dole strony, w sekcji **Artifacts**, pobierz `SphereSweeper-debug-apk`.
4. Rozpakuj archiwum ZIP i zainstaluj plik `.apk` na telefonie.

Do instalacji spoza Sklepu Play telefon musi zezwalać na instalację z nieznanych źródeł.

## Wydania (release)

Wydanie tworzy tag zaczynający się od `v`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow uruchamia wtedy dodatkowe zadanie `release`.
Zadanie buduje APK release i dołącza go do nowego wydania w zakładce **Releases**.

- Z ustawionymi sekretami podpisu plik nazywa się `SphereSweeper-v1.0.0.apk` i jest podpisany Twoim kluczem.
- Bez sekretów plik nazywa się `SphereSweeper-v1.0.0-debug-signed.apk` i jest podpisany kluczem debug. Workflow nie kończy się wtedy błędem.

Przed kolejnym wydaniem zwiększ `versionCode` i `versionName` w `app/build.gradle.kts`.
Android nie zainstaluje aktualizacji z tym samym lub niższym `versionCode`.

## Podpisywanie: klucz i sekrety

### 1. Utwórz magazyn kluczy (keystore)

Keystore to plik z Twoim kluczem prywatnym. Narzędzie `keytool` jest częścią JDK.

```bash
keytool -genkeypair -v \
  -keystore sphere-sweeper-release.jks \
  -alias sphere-sweeper \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

`keytool` zapyta o hasło magazynu, hasło klucza i dane właściciela.
Zapisz oba hasła i alias w menedżerze haseł.
Plik `.jks` przechowuj poza repozytorium (`.gitignore` ignoruje `*.jks` i `*.keystore`).
Utrata klucza oznacza, że nie wydasz już aktualizacji tej samej aplikacji.

### 2. Zakoduj keystore w base64

macOS:

```bash
base64 -i sphere-sweeper-release.jks | pbcopy
```

Linux:

```bash
base64 -w 0 sphere-sweeper-release.jks > keystore.base64.txt
```

Na macOS wynik trafia od razu do schowka. Na Linuksie skopiuj zawartość pliku `keystore.base64.txt`, a potem go usuń.

### 3. Dodaj sekrety w GitHubie

Przejdź do **Settings → Secrets and variables → Actions → New repository secret** i dodaj cztery sekrety:

| Sekret | Wartość |
|--------|---------|
| `KEYSTORE_BASE64` | Tekst base64 z kroku 2 |
| `KEYSTORE_PASSWORD` | Hasło magazynu kluczy |
| `KEY_ALIAS` | Alias klucza, np. `sphere-sweeper` |
| `KEY_PASSWORD` | Hasło klucza |

Workflow dekoduje keystore do katalogu tymczasowego runnera i przekazuje Gradle zmienne
`SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` i `SIGNING_KEY_PASSWORD`.
Te same zmienne możesz ustawić lokalnie, żeby zbudować podpisany APK na swoim komputerze:

```bash
export SIGNING_STORE_FILE="$PWD/sphere-sweeper-release.jks"
export SIGNING_STORE_PASSWORD='...'
export SIGNING_KEY_ALIAS='sphere-sweeper'
export SIGNING_KEY_PASSWORD='...'
./gradlew assembleRelease
```

## Po pierwszym przebiegu CI — checklista i typowe błędy

Checklista:

1. Zadanie `build` jest zielone, a w sekcji **Artifacts** widać `SphereSweeper-debug-apk`.
2. APK z artefaktu instaluje się i uruchamia na telefonie.
3. W logu kroku „Run unit tests and assemble debug APK” testy jednostkowe przeszły (`BUILD SUCCESSFUL`).
4. Plik `gradlew` i katalog `gradle/wrapper/` są w repozytorium, a `gradlew` ma prawo wykonywania.
5. Po wypchnięciu tagu `v*` zadanie `release` dodało APK do wydania w zakładce **Releases**.
6. Nazwa pliku w wydaniu nie kończy się na `-debug-signed`, jeśli ustawiłeś sekrety podpisu.

Typowe błędy:

- **Niezgodność wersji Kotlina i kompilatora Compose.**
  Komunikat: `This version of the Compose Compiler requires Kotlin version ...`.
  Kompilator Compose 1.5.14 działa tylko z Kotlinem 1.9.24.
  Zmieniasz jedną wersję w `gradle/libs.versions.toml`? Zmień też drugą według tabeli zgodności Compose–Kotlin.
- **Brak wrappera albo brak prawa wykonywania.**
  Komunikat: `./gradlew: No such file or directory` albo `Permission denied`.
  Workflow sam generuje wrapper (`gradle wrapper --gradle-version 8.7`) i nadaje prawo `chmod +x`.
  Lokalnie wygeneruj go tym samym poleceniem i dodaj do repozytorium: `git add gradlew gradlew.bat gradle/wrapper` oraz `git update-index --chmod=+x gradlew`.
- **Wydanie podpisane kluczem debug.**
  Plik kończy się na `-debug-signed`, a log zawiera ostrzeżenie o brakujących sekretach.
  Sprawdź, czy wszystkie cztery sekrety istnieją i mają dokładnie te nazwy.
  Sprawdź też, czy `KEYSTORE_BASE64` to jedna linia bez dodatkowych znaków.
  Błędne hasło lub alias kończy się błędem `assembleRelease` (np. `Keystore was tampered with, or password was incorrect`). Wtedy popraw sekret i uruchom zadanie ponownie.
- **Zła wersja JDK.**
  Komunikat: `Android Gradle plugin requires Java 17 to run` albo `Unsupported class file major version`.
  AGP 8.5.2 wymaga JDK 17. Lokalnie ustaw `JAVA_HOME` na JDK 17. W Android Studio wybierz JDK 17 w ustawieniach Gradle.
- **Licencja Android SDK.**
  Komunikat: `Failed to install the following Android SDK packages as some licences have not been accepted`.
  Lokalnie uruchom `sdkmanager --licenses` i zaakceptuj licencje.
  Na runnerach GitHuba `ubuntu-latest` SDK i licencje są już zainstalowane.
- **Brak SDK lokalnie.**
  Komunikat: `SDK location not found`.
  Ustaw `ANDROID_HOME` albo dodaj `sdk.dir=/ścieżka/do/Android/sdk` w pliku `local.properties`.

## Struktura projektu

- `app/src/main/java/com/example/spheresweeper/logic/` — geometria sfery i zasady gry, czysty Kotlin bez zależności od Androida.
- `app/src/test/java/com/example/spheresweeper/logic/` — testy jednostkowe JUnit 4.
- `app/src/main/java/com/example/spheresweeper/ui/` — ekrany Compose, rysowanie kuli i obsługa gestów.
- `.github/workflows/build.yml` — CI: testy, APK debug i wydania z tagów.
