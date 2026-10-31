# 15 – Ověření P2P Reticulum Mesh Spojení a Skupinového Dashboardu

**Datum**: 30. října 2026  
**Stav**: ✅ Dokončeno a ověřeno live na zařízeních  
**Testovaná zařízení**:
- Fyzický telefon: Xiaomi POCO X3 NFC (`5423d6ad`), Android 16 (SDK 36)
- Emulátor: Android Virtual Device (`emulator-5554`), Android 14 (API 34)

---

## 1. Cíle testu
1. **Ověřit vzájemné P2P spojení přes Reticulum**:
   - Výměna kryptografických identit a veřejných klíčů přes formát Pairing Payload.
   - Navázání oboustranného spojení přes Reticulum transportní vrstvu (HDLC přes TCP / AutoInterface).
   - Odeslání a přijetí šifrovaných telemetrických balíčků (váha, spánek, Body Battery, HRV).
2. **Ověřit Skupinový Dashboard (Group Metrics Dashboard)**:
   - Založení sdílené skupiny (`Testovaci_Skupina`).
   - Nastavení oprávnění pro sdílení dat (`ShareGrant`).
   - Zobrazení účastníků, jejich nejnovějších hodnot a 5denních trendů v UI.

---

## 2. Architektura a řešení zjištěných úskalí

### A. Řešení DNS rozlišení v embedded Pythonu (Chaquopy)
* **Problém**: Při pokusu o připojení na hostitelská jména (`rns.xali.cz`) vracela knihovna `socket.getaddrinfo` v prostředí Android bionic libc chybu `[Errno 7] No address associated with hostname`.
* **Řešení**: V [`ReticulumBridge.kt`](file:///home/kocourkovic/projects/pulse4/app/src/main/kotlin/app/pulse4/android/reticulum/ReticulumBridge.kt) jsme doplnili předběžné rozlišení hostu přes standardní Android Java DNS API (`InetAddress.getByName`), které spolehlivě využívá systémové nastavení DNS Androidu. Python tak dostává přímo cílovou IPv4 adresu.

### B. Místní transport a směrování přes ADB reverse
* **Problém**: Na některých Wi-Fi sítích blokuje router odchozí nestandardní TCP porty (4242).
* **Řešení**: Využili jsme schopnost Reticula směrovat přes lokální TCP bránu:
  - Telefon se přes USB připojuje na `127.0.0.1:4242` přes tunel `adb reverse tcp:4242 tcp:4242`.
  - Emulátor se připojuje na `10.0.2.2:4242`.
  - Mezi nimi běží lokální Reticulum TCP Hub (`tools/rns_hub.py`), který vysílá HDLC rámce mezi oběma zařízeními.
  - Obě rozhraní `TCPInterface` i `AutoInterface[Local]` byla na obou zařízeních současně ve stavu `online: true`.

### C. Bezpečnost Room databáze a vlákna
* **Problém**: Volání změn nastavení přímo z callbacku intentu na hlavním vlákně vyvolalo `IllegalStateException: Cannot access database on the main thread`.
* **Řešení**: Veškeré operace zápisu nastavení, kontaktů a grantů v [`MainActivity.kt`](file:///home/kocourkovic/projects/pulse4/app/src/main/kotlin/app/pulse4/android/ui/MainActivity.kt) byly důsledně obaleny do `CoroutineScope(Dispatchers.IO).launch`.

---

## 3. Průběh a výsledky párování

### Identita uzlů (Reticulum Delivery Hash):
- **POCO Telefon**: `e54f4425e22820fd9f282bb1a8138f3c`
  - Veřejný klíč (Ed25519/X25519 64B): `OWk44rWO70zAKf9XiAMyS0f59EW0x0TOS6ubaw8olyUOf9GjPddB6NAnCiZaYPlYQSMLsDQ6L5cTQaoi1sYysA==`
- **Emulátor Host**: `6b34af06ceb10ed54214628b457eec9a`
  - Veřejný klíč (Ed25519/X25519 64B): `DpvTHr0wzAoJvIXZqcQgnuNXFPcrDgZgltKN/AEER01IvgzGDBzwkPSCmpZaIYO+BA8ed2++ny9J4WL+mruGFg==`

Po předání Base64 párovacích payloadů:
- Na emulátoru: `Contact added: POCO Telefon -> e54f4425e22820fd9f282bb1a8138f3c`
- Na telefonu: `Contact added: Emulátor Host -> 6b34af06ceb10ed54214628b457eec9a`

---

## 4. Skupinový Dashboard – Živé ověření v UI

Na emulátoru byla v záložce **Skupiny → 📊 Dashboard** vybrána skupina `👥 Testovaci_Skupina (2)`:
1. **Účastníci skupiny**:
   - `👤 POCO Telefon` označený jako `Kontakt`
2. **Přepínání metrik**:
   - **⚖️ Váha**: Zobrazena hodnota **75,0 kg** s poznámkou `Měření z 29. 10. 19:25 · přijato přes P2P`, 5denní historie a rozsah `74 – 75 kg`.
   - **🛌 Spánek**: Zobrazena hodnota **7 h 46 min** s poznámkou `přijato přes P2P`, 5denní historie a trendový graf.
   - **⚡ Body Battery**: Zobrazena hodnota **68** s rozsahem `68 – 88`.
   - **❤️ Klidový tep** & **💓 HRV**: Plynulé přepínání metrik bez prodlevy.

Snímky obrazovky jsou uloženy v [attachments/](file:///home/kocourkovic/projects/pulse4/docs/attachments/):
- `emu_group_dashboard_detail.png`
- `emu_group_sleep.png`
