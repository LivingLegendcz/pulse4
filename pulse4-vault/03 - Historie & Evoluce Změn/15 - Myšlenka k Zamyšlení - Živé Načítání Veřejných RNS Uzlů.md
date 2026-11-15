# 15 – Myšlenka k zamyšlení: Živé načítání veřejných RNS uzlů v aplikaci

**Datum:** 2026-11-15  
**Stav:** Návrh / backlog k budoucímu zvážení

---

## Kontext
V aktuální verzi (`0.1.8`) uživatel v Nastavení vidí odkazy na komunitní seznamy (`rmap.world`, `directory.rns.recipes`) a statické příklady s možností ručního ověření TCP dostupnosti. Pokud by se však v aplikaci na vyžádání načetl živý seznam uzlů přímo do UI, uživatel by mohl kliknout jedním klepnutím na **„Přidat“** bez ručního kopírování adresy.

---

## Technická proveditelnost
- **Realizovatelnost:** Vysoká (nízká náročnost implementace přes stávající `OkHttpClient`).
- **Princip fungování:**
  1. Na výslovné klepnutí uživatele (**„Načíst aktuální uzly z komunity“**) aplikace stáhne data z komunitního adresáře (např. `directory.rns.recipes` / `rmap.world`).
  2. Vyparsuje pouze kompatibilní **`TCPClientInterface`** záznamy (`target_host`, `target_port`, název) a odfiltruje I2P, Yggdrasil nebo lokální LoRa/RNode rozhraní.
  3. Zobrazí karty uzlů s tlačítkem **„Přidat“** a možností rychlého TCP testu (`probeNodeReachability`).

---

## Rizika a podmínky k promyšlení
1. **Absence garantovaného JSON API:** Komunitní adresáře primárně publikují HTML nebo `.ini` bloky; změna struktury může vyžadovat aktualizaci parseru (nutný fallback na otevření v prohlížeči).
2. **Zero Cloud Dependency:** Stahování nesmí nikdy běžet automaticky na pozadí při startu aplikace — vždy pouze po vědomém kliknutí uživatele.
3. **Důvěryhodnost seznamu:** Komunitní adresáře nejsou kryptograficky podepsané; uzel musí zůstat označen jako *infrastruktura třetí strany* (obsah zpráv v Pulse4 zůstává E2EE šifrovaný).
