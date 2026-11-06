# Alexet

**English** · [Polski](#polski)

Alexet is a gallery app for Android, built with Kotlin and Jetpack Compose. It is made for phones, foldables and tablets alike: on a wide screen the folders and their contents sit side by side, and the layout follows the hinge of a foldable.

Requires Android 8.0 (API 26) or newer.

## How to install

You don't need a GitHub account. Do this on your phone:

1. Open the **[latest release](https://github.com/Anderinfera/Alexet/releases/latest)** page.
2. Scroll down to **Assets** (tap it if the list is folded) and tap **`Alexet-release.apk`**. The file downloads to your phone.
3. When the download finishes, tap the notification or open the file from the **Downloads** folder.
4. Android may say that your browser is not allowed to install apps. Tap **Settings**, turn on **Allow from this source**, then go back.
5. Tap **Install**, then **Open**.
6. Tap **Grant permission** and allow access to photos and videos, so Alexet can show your gallery.

**AI search (optional).** It lets you find photos by describing them, for example "dog on the beach". Everything runs on your phone.

1. On the same release page, under **Assets**, download **`siglip2-b16-224.alexetai`** (about 560 MB, Wi-Fi recommended).
2. In Alexet tap the gear icon at the top to open **Settings**, then **Extra** → **AI search** → **Import AI model** and pick the downloaded file.
3. Alexet then indexes your photos in the background, faster while the phone is charging.

**Updating.** Download the newer `Alexet-release.apk` the same way and install it over the old one. Your settings and folders stay.

## Features

### Gallery and folders
- Folder grid with an adjustable number of columns (1–10) and a separate column count for files.
- Two-pane layout on foldables and tablets, with an adjustable split (10%–90%) and an automatic 50/50 split when the device is half folded (adjustable hinge angle).
- Content views for the right pane: recently opened folder, current contents, wide view (an N×M folder grid over the whole screen) and multi-folder mode (open folders by tapping or dragging them to the left or right pane).
- Sorting by name, path, size, date modified, date taken or randomly, ascending or descending; per-folder sorting and grouping.
- Custom folder order (hold and drag) and custom order of files inside a folder.
- Full scroller on the left or right side, with optional smooth scrolling.
- Folder info: path, number of files and folders, total video length, dates created and last used.
- Favorites folder.
- "See all files": documents, archives and APKs in folders, with an option to also show folders that hold only other files.
- Archive browser for ZIP, 7Z, TAR, TAR.GZ and RAR, with unpacking of single files.
- APK files open in the system installer.

### Files
- Quick select, rectangle (2D) or linear (1D) selection style, batch actions in tap order.
- Copy and move between folders, with progress in a notification; drag files with two fingers.
- Rename one file or many: add text before or after the name, replace a part of it, swap two names, or use a pattern with date, time and a counter (`%Y %M %D %h %m %s %i`).
- Trash with restore; folders are deleted or moved to the trash in the background, with progress and cancel.
- Share, with optional EXIF removal before sending.
- Remove EXIF data (GPS, camera model) while keeping the date.
- Set as wallpaper for the home screen, lock screen or both, with a crop frame for the folded and unfolded screens and device presets.

### Privacy
- Private folders: an AES-256-GCM encrypted vault (`.enc`) hidden from the system gallery, unlocked with biometrics or a password, with an optional backup password and password hint.
- Permanent decryption back to a normal folder.
- Nothing decrypted stays in memory after the folder locks.
- Hidden folders (`.nomedia`): show or hide them, hide or unhide a folder.
- Hidden folder covers: pixelation (N×N), blur, a chosen picture, a plain color, stripes or a checkerboard over the cover. Stripes and checkerboard have adjustable frequency, width, rotation and color, and can get a random orientation and/or color on every app start, limited to a hue range or a custom palette.
- Name mixer: files added to chosen folders get random names.

### Viewer and player
- Photo viewer with swipe navigation, an optional one-finger edge swipe and optional side arrows that switch photos without animation.
- GIF and animated image playback.
- Built-in video player: volume and brightness swipe gestures, hold to speed up, double tap to seek with an adjustable step, playback speed, lock controls, controls auto-hide time.
- SponsorBlock segments read from chapters saved in the video file, with automatic skipping per category (sponsor, self-promotion, interaction reminder, intro, outro, preview, hook, filler, non-music section) and a Skip button for the others.
- Audio files play in the built-in player with a Material You vinyl record and equalizer visual.
- Video duration shown as minutes (12:34) or full (0:12:34).

### Photo editor
- Crop and rotate, with free, exact or fixed aspect ratios (1:1, 16:9, 16:10, 3:2, 2:1, 18:9, 21:9, 5:4).
- Adjustments: brightness, contrast, saturation, shadows, highlights, exposure, gamma, temperature and sharpness, with a live preview.
- Blur: radial, mirror, linear or Gaussian.
- Brush with color, size and opacity, and an eyedropper.
- Text and stickers (images from the gallery).
- Compare with another photo.
- Undo and redo; images too large to edit are saved as a copy.

### Video editor
- Timeline with several clips, trim handles on the edges and split.
- Speed, clip volume, muted original audio and an extra audio track.
- Text with color, moved with one finger.
- Framing: fill or whole picture, Fit.
- Export to video or GIF with a chosen format and resolution, in the background with a notification; the project survives the app being closed.

### AI search
- Find photos by describing them, on the device only. Uses SigLIP 2 on LiteRT; the model is imported as an `.alexetai` file.
- Photo index built in the background while the app is open or the phone is charging; pause, resume or delete it.
- Choose which folders are indexed: all, selected, or all except some.
- AI effects: glow and animations of the search, with presets, color palettes, brightness, speed and width.

### Settings
- Settings grouped in categories, with search that opens and highlights the found option.
- Live previews of the view options for phone and tablet.
- Light mode that turns off animations on slow phones (offered automatically when the app runs slowly).
- Sharper thumbnails, thumbnail cache refresh.
- Export and import of all settings.
- Polish and English.

## Integrations\*

- **NewPipe Enhanced\*** (a PipePipe fork)
  - Download progress shown on the files while they download; unfinished files are locked until they are complete.
  - SponsorBlock segments saved in downloaded videos as chapters, skipped in the built-in player.
  - A watched NewPipe folder, so new downloads show up right away.
- **Anderyer\*** (a Next Player fork)
  - Opens videos from Alexet in Anderyer.
  - Player settings shared through AnderLine: gestures, seek step, shortcuts, SponsorBlock and more.

\* Requires my port of the app to work.

---

<a id="polski"></a>

# Alexet

[English](#alexet) · **Polski**

Alexet to galeria na Androida napisana w Kotlinie i Jetpack Compose. Działa tak samo dobrze na telefonie, składanym telefonie i tablecie: na szerokim ekranie foldery i ich zawartość są obok siebie, a układ dopasowuje się do zawiasu składanego telefonu.

Wymaga Androida 8.0 (API 26) lub nowszego.

## Jak zainstalować

Konto na GitHubie nie jest potrzebne. Zrób to na telefonie:

1. Otwórz stronę **[najnowszej wersji](https://github.com/Anderinfera/Alexet/releases/latest)**.
2. Przewiń w dół do **Assets** (dotknij, jeśli lista jest zwinięta) i dotknij **`Alexet-release.apk`**. Plik pobierze się na telefon.
3. Po pobraniu dotknij powiadomienia albo otwórz plik z folderu **Pobrane**.
4. Android może napisać, że przeglądarka nie może instalować aplikacji. Dotknij **Ustawienia**, włącz **Zezwalaj z tego źródła** i wróć.
5. Dotknij **Zainstaluj**, a potem **Otwórz**.
6. Dotknij **Udziel uprawnienia** i zezwól na dostęp do zdjęć i filmów, żeby Alexet mógł pokazać galerię.

**Wyszukiwanie AI (opcjonalnie).** Pozwala znaleźć zdjęcia po opisie, np. „pies na plaży”. Wszystko działa na telefonie.

1. Na tej samej stronie, w **Assets**, pobierz **`siglip2-b16-224.alexetai`** (ok. 560 MB, najlepiej przez Wi-Fi).
2. W Alexecie dotknij ikony koła zębatego u góry, żeby otworzyć **Ustawienia**, potem **Dodatkowe** → **Wyszukiwanie AI** → **Wgraj model AI** i wybierz pobrany plik.
3. Alexet zacznie indeksować zdjęcia w tle, szybciej gdy telefon się ładuje.

**Aktualizacja.** Pobierz nowszy `Alexet-release.apk` tak samo i zainstaluj go na starym. Ustawienia i foldery zostają.

## Funkcje

### Galeria i foldery
- Siatka folderów z wybieraną liczbą kolumn (1–10) i osobną liczbą kolumn dla plików.
- Widok dwupanelowy na składanych telefonach i tabletach z regulowanym podziałem (10%–90%) i automatycznym podziałem 50/50 przy częściowym złożeniu (regulowany kąt zawiasu).
- Widoki zawartości w prawym panelu: ostatnio otwarty folder, bieżąca zawartość, widok szeroki (siatka folderów N×M na cały ekran) i tryb wielu folderów (otwieranie folderów dotknięciem lub przeciągnięciem na lewy lub prawy panel).
- Sortowanie po nazwie, ścieżce, rozmiarze, dacie modyfikacji, dacie wykonania lub losowo, rosnąco albo malejąco; sortowanie i grupowanie dla pojedynczego folderu.
- Własna kolejność folderów (przytrzymaj i przeciągnij) i własna kolejność plików w folderze.
- Pełny suwak przewijania po lewej lub prawej stronie, z opcją płynnego przewijania.
- Informacje o folderze: ścieżka, liczba plików i folderów, łączna długość filmów, data utworzenia i ostatniego użycia.
- Folder Ulubione.
- „Pokaż wszystkie pliki”: dokumenty, archiwa i pliki APK w folderach, z opcją pokazywania też folderów, w których są tylko inne pliki.
- Przeglądanie archiwów ZIP, 7Z, TAR, TAR.GZ i RAR z rozpakowywaniem pojedynczych plików.
- Pliki APK otwierają się w instalatorze systemowym.

### Pliki
- Szybkie zaznaczanie, styl zaznaczania prostokątny (2D) lub liniowy (1D), akcje zbiorcze w kolejności dotknięć.
- Kopiowanie i przenoszenie między folderami z postępem w powiadomieniu; przeciąganie plików dwoma palcami.
- Zmiana nazwy jednego lub wielu plików: tekst przed lub po nazwie, zamiana fragmentu, zamiana dwóch nazw miejscami albo wzór z datą, godziną i licznikiem (`%Y %M %D %h %m %s %i`).
- Kosz z przywracaniem; foldery są usuwane lub przenoszone do kosza w tle, z postępem i możliwością anulowania.
- Udostępnianie z opcjonalnym usuwaniem EXIF przed wysłaniem.
- Usuwanie danych EXIF (GPS, model aparatu) z zachowaniem daty.
- Ustawianie tapety na ekranie głównym, blokady lub obu, z kadrowaniem dla ekranu złożonego i rozłożonego oraz gotowymi ustawieniami urządzeń.

### Prywatność
- Foldery prywatne: sejf szyfrowany AES-256-GCM (`.enc`) ukryty przed systemową galerią, odblokowywany biometrią lub hasłem, z opcjonalnym hasłem zapasowym i podpowiedzią.
- Trwałe odszyfrowanie z powrotem do zwykłego folderu.
- Po zablokowaniu folderu nic odszyfrowanego nie zostaje w pamięci.
- Foldery ukryte (`.nomedia`): pokazywanie lub chowanie ich, ukrywanie i odkrywanie folderu.
- Zamazane okładki ukrytych folderów: pikselizacja (N×N), rozmazanie, wybrany obraz, jednolity kolor, pasy lub szachownica na okładce. Pasy i szachownica mają regulowaną częstotliwość, szerokość, obrót i kolor, a przy każdym uruchomieniu mogą dostać losową orientację i/lub kolor, ograniczony do zakresu barw albo własnej palety.
- Mikser nazw: pliki dodane do wybranych folderów dostają losowe nazwy.

### Przeglądarka i odtwarzacz
- Przeglądarka zdjęć z przesuwaniem, opcjonalnym przesunięciem jednym palcem od krawędzi i opcjonalnymi strzałkami po bokach, które przełączają zdjęcia bez animacji.
- Odtwarzanie GIF-ów i animowanych obrazów.
- Wbudowany odtwarzacz wideo: gesty głośności i jasności, przytrzymanie dla przyspieszenia, podwójne dotknięcie do przewijania z regulowanym krokiem, prędkość odtwarzania, blokada sterowania, czas ukrywania przycisków.
- Segmenty SponsorBlock odczytywane z rozdziałów zapisanych w pliku wideo, z automatycznym pomijaniem dla wybranych kategorii (sponsor, autopromocja, przypomnienie o interakcji, intro, outro, zapowiedź, haczyk, wypełniacz, fragment bez muzyki) i przyciskiem „Pomiń” dla pozostałych.
- Pliki audio grają we wbudowanym odtwarzaczu z płytą winylową i korektorem w kolorach Material You.
- Długość filmów w minutach (12:34) albo pełna (0:12:34).

### Edytor zdjęć
- Kadrowanie i obracanie, z proporcjami dowolnymi, dokładnymi lub stałymi (1:1, 16:9, 16:10, 3:2, 2:1, 18:9, 21:9, 5:4).
- Regulacje: jasność, kontrast, nasycenie, cienie, światła, ekspozycja, gamma, temperatura i ostrość, z podglądem na żywo.
- Rozmycie: promieniowe, lustrzane, liniowe lub gaussowskie.
- Pędzel z kolorem, rozmiarem i kryciem oraz pipeta.
- Tekst i naklejki (obrazy z galerii).
- Porównanie z innym zdjęciem.
- Cofnij i ponów; obrazy za duże do edycji zapisują się jako kopia.

### Edytor wideo
- Oś czasu z wieloma klipami, uchwytami przycinania na krawędziach i dzieleniem.
- Prędkość, głośność klipu, wyciszenie oryginalnego dźwięku i dodatkowa ścieżka audio.
- Tekst z kolorem, przesuwany jednym palcem.
- Kadrowanie: wypełnienie lub cały obraz, Dopasuj.
- Eksport do wideo lub GIF-a z wybranym formatem i rozdzielczością, w tle z powiadomieniem; projekt przetrwa zamknięcie aplikacji.

### Wyszukiwanie AI
- Szukanie zdjęć po opisie, wyłącznie na urządzeniu. Działa na SigLIP 2 i LiteRT; model importuje się jako plik `.alexetai`.
- Indeks zdjęć budowany w tle, gdy aplikacja jest otwarta albo telefon się ładuje; można go wstrzymać, wznowić lub usunąć.
- Wybór indeksowanych folderów: wszystkie, wybrane albo wszystkie oprócz niektórych.
- Efekty AI: poświata i animacje wyszukiwania, z gotowymi stylami, paletami kolorów, jasnością, prędkością i szerokością.

### Ustawienia
- Ustawienia w kategoriach, z wyszukiwarką, która otwiera i podświetla znalezioną opcję.
- Podglądy na żywo opcji widoku dla telefonu i tabletu.
- Tryb lekki, który wyłącza animacje na słabszych telefonach (proponowany automatycznie, gdy aplikacja działa wolno).
- Ostrzejsze miniatury, odświeżanie pamięci podręcznej miniatur.
- Eksport i import wszystkich ustawień.
- Polski i angielski.

## Integracje\*

- **NewPipe Enhanced\*** (fork PipePipe)
  - Postęp pobierania widoczny na plikach w trakcie pobierania; niedokończone pliki są zablokowane, dopóki się nie pobiorą.
  - Segmenty SponsorBlock zapisane w pobranych filmach jako rozdziały i pomijane we wbudowanym odtwarzaczu.
  - Obserwowany folder NewPipe, dzięki czemu nowe pobrania pojawiają się od razu.
- **Anderyer\*** (fork Next Player)
  - Otwieranie filmów z Alexeta w Anderyerze.
  - Ustawienia odtwarzacza współdzielone przez AnderLine: gesty, krok przewijania, skróty, SponsorBlock i inne.

\* Wymaga mojego portu aplikacji do działania.

---

## License

Alexet is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

The AI search model (SigLIP 2 by Google) and LiteRT are licensed under the Apache License 2.0.
