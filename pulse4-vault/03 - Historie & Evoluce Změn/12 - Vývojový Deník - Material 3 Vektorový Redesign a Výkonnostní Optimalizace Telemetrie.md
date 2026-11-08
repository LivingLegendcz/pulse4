# Vývojový Deník: Material 3 Vektorový Redesign a Výkonnostní Optimalizace Telemetrie

**Datum:** 8. listopadu 2026  
**Autor:** Antigravity & Pulse4 Core Team  
**Cíl:** Kompletní odstranění emoji glyphů a náhrada za Google Material 3 Vector Icons (`androidx.compose.material.icons`), eliminace grafických a výpočetních úzkých hrdel způsobujících zpomalení aplikace při scrollování a renderování telemetrických grafů.

---

## 1. Výchozí Stav a Root Cause Analysis (Proč se aplikace zpomalila?)

Při profilování aplikace pomocí `dumpsys gfxinfo app.pulse4` na emulátoru a fyzickém zařízení POCO X3 NFC byly identifikovány 3 hlavní příčiny zpomalení UI:

### A) Neustálá invalidace Compose stromu na 120 FPS (`rememberInfiniteTransition`)
V komponentě `GarminTrainingEffectCard` byl zaveden `rememberInfiniteTransition`, který každých 8–16 ms animoval proměnnou `dotPulse` pro pulzující bod ve 2D Firstbeat matici.
- **Dopad:** Nepřetržitá invalidace fáze `layout` a `draw` nejen v kartě, ale i v jejím nadřazeném kontejneru a okolních elementech. CPU a GPU neměly možnost přejít do úsporného stavu, což způsobovalo masivní nárůst `Number Slow UI thread: 105` a `Number Slow issue draw commands: 153`.

### B) Off-screen Bezier křivky s 5 000 body mimo plátno u Staminy
Při zobrazování sekundární křivky potenciální staminy v `LineChart` byla předána data `track.map { it.staminaPotentialPct }` obsahující přes 5 000 bodů, zatímco primární křivka měla 160 převzorkovaných bucketů.
- **Dopad:** `LineChart` počítal pozici x vzorcem `(index / (values.size - 1)) * width`. Pro body s indexem 160–5000 generoval Canvas 5 000 segmentů `cubicTo` kreslených desítky obrazovek doprava mimo plátno! GPU vlákno trávilo na jediný frame až **4 950 ms** (`99th gpu percentile: 4950ms`).

### C) Těžké synchronní O(N²) výpočty na UI vlákně
Funkce `RunningDynamicsModel.findBestEfforts(track)`, `computeGapSpeedMps` a `computeStravaRelativeEffort` byly volány přímo v těle Compose funkce `ActivityDetailScreen` v bloku `remember(track)`.
- **Dopad:** Při prvním vykreslení a každé rekompozici docházelo k mikro-zaseknutí UI vlákna.

---

## 2. Implementované Výkonnostní Úpravy

1. **Předpočítání metrik na IO vlákně na pozadí:**
   - Datová třída `LoadedActivityDetail` byla rozšířena o pole:
     - `val relativeEffort: Int`
     - `val bestEfforts: List<BestEffort>`
     - `val gapPaceSPerKm: Double?`
   - Ve funkci `loadUnifiedActivity(sl, key)` probíhají výpočty Best Efforts a Relative Effort jednorázově na pozadí coroutiny `Dispatchers.IO`. UI thread pouze okamžitě čte hotová data z `LoadedActivityDetail`.

2. **Konzistentní resamplování sekundárních křivek (`buckets = 160`):**
   - Sekundární hodnoty staminy jsou nyní převzorkovány pomocí `chChart.trackIndex.map { ti -> track.getOrNull(ti)?.staminaPotentialPct }`.
   - Obě křivky v `LineChart` mají identický počet 160 bodů a sdílí stejnou souřadnou síť plátna bez alokací off-screen křivek.

3. **Statický Neonový Punkt Firstbeat bez trvalých animací:**
   - Odstraněn `rememberInfiniteTransition` a `dotPulse`.
   - Nahrazeno precizně vrstveným neonovým kruhem (`drawCircle` s korónou o poloměru 16f, prstencem 9f, jádrem 6f a bílým středem 3.2f). Nulová režie na UI vlákně.

---

## 3. Kompletní Material 3 Vektorový Redesign (Odstranění Emoji)

Všechny neformální emoji znaky byly nahrazeny Google Material 3 vektorovými ikonami (`androidx.compose.material.icons`):

| UI Element | Původní Emoji | Nová Material 3 Vektorová Ikona |
|---|---|---|
| Tlačítko Zpět | `← Zpět` | `Icons.AutoMirrored.Outlined.ArrowBack` + Text |
| Záložka Přehled | `🗺️ Přehled & Mapa` | `FilterChip(leadingIcon = { Icon(Icons.Outlined.Map) })` |
| Záložka Grafy | `📈 Grafy & Zóny` | `FilterChip(leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ShowChart) })` |
| Záložka Tabulky | `📋 Tabulky & Statistiky` | `FilterChip(leadingIcon = { Icon(Icons.Outlined.TableChart) })` |
| Záložka Mezičasy | `🏁 Mezičasy & Kola` | `FilterChip(leadingIcon = { Icon(Icons.Outlined.Flag) })` |
| Tlačítko Garmin sync | `🔄 Garmin` | `Icon(Icons.Outlined.Sync)` + "Garmin" |
| Tlačítko Import FIT | `📂 FIT` | `Icon(Icons.Outlined.FolderOpen)` + "FIT" |
| Tlačítko Porovnat | `📊 Porovnat` | `Icon(Icons.AutoMirrored.Outlined.CompareArrows)` |
| Filtr Moje / P2P | `👤 Moje` / `📡 Sdílené P2P` | `Icon(Icons.Outlined.Person)` / `Icon(Icons.Outlined.Share)` |
| Start / Cíl trasy | `🟢 Start` / `🏁 Cíl` | Barevné kruhové akcenty `Box(CircleShape)` + ostrý typografický štítek |
| Hlavičky sekcí | `❤️`, `🔋`, `⛰️`, `🦶`, `🔥` | `Icons.Outlined.Favorite`, `Bolt`, `Terrain`, `DirectionsRun`, `LocalFireDepartment` |
| Odznaky informací | `Text("ℹ")` | `Icon(Icons.Outlined.Info, tint = Color(0xFF38BDF8))` |
| Medaile milníků | `🥇`, `🥈`, `🥉` | Elegantní kruhové čipy `#1`, `#2`, `#3` (Zlato, Stříbro, Bronz) |

---

## 4. Výsledky Výkonnostních Testů (Benchmark Gfxinfo)

Měření proběhlo pomocí `dumpsys gfxinfo app.pulse4` po standardizovaném rychlém scrollování a interakci na fyzickém telefonu Xiaomi POCO X3 NFC (`5423d6ad`):

| Metrika | Před Optimalizací | Po Optimalizaci | Zlepšení |
|---|---|---|---|
| **Janky frames (%)** | **20.16 %** (legacy 73.73 %) | **0.87 %** (legacy 0.00 %) | **-95.7 % pokles trhání!** |
| **50th percentile (medián)** | 29 ms | **15 ms** | Zrychlení na 60 FPS |
| **90th percentile** | 85 ms | **16 ms** | Konzistentní plynulost |
| **95th percentile** | 117 ms | **17 ms** | Eliminace lagů |
| **99th percentile frame time** | **450 ms** | **17 ms** | **-96.2 % redukce špiček!** |
| **Number Slow UI thread** | 105 | **0** | **100 % odstranění stallů UI** |
| **Number Slow issue draw** | 153 | **0** | **100 % odstranění stallů GPU draw** |
| **99th GPU percentile** | **4 950 ms** | **11 ms** | **-99.8 % zátěže GPU!** |

---

## 5. Fotodokumentace Změn

- `attachments/screen_phone_activities_material.png` – Seznam 500 aktivit s čistými Material tlačítky a filtry
- `attachments/screen_phone_detail_tab0.png` – Detail aktivity s mapou a Material 3 navigační lištou
- `attachments/screen_phone_detail_te.png` – Garmin Firstbeat matice s neonovým statickým bodem
- `attachments/screen_phone_tab1_charts.png` – Převzorkované telemetrické grafy s okamžitou 60 FPS odezvou
