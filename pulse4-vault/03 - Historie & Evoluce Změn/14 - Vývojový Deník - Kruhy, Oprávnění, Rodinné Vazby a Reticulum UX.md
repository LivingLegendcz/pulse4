# 14 – Vývojový Deník: Kruhy, Oprávnění, Rodinné Vazby a Reticulum UX

**Datum:** 2026-11-15  
**Stav:** Implementováno, otestováno (`./gradlew test :app:assembleDebug` + `:app:connectedDebugAndroidTest` na emulátoru `pulse4_emu` prošlo), vydáno jako `Pulse4 0.1.7` na Google Play `internal` track.

### Doplnění (Notifikace nedostupného uzlu & Emulator `androidTest`)
- **`ReticulumBridge.kt` & `MainActivity.kt`:** Přidán `StateFlow<GatewayHealth>` (`UNCONFIGURED`, `CONNECTING`, `ONLINE`, `UNREACHABLE`). Pokud nakonfigurovaný RNS uzel přestane existovat nebo je nedostupný (mimo `LOCAL_ONLY`), zobrazí se v horní části aplikace notifikační lišta *„Připojovací uzel ... je nedostupný“* s tlačítkem **„Vybrat uzel“** (přechod do Nastavení) a v `SettingsHubScreen.kt` přímé klikací odkazy na `rmap.world` a `directory.rns.recipes`.
- **Instrumentované testy v emulátoru (`app/src/androidTest/`):**
  - `DatabaseMigrationTest.kt`: ověření Room `MIGRATION_2_3` na reálném Android SQLite runtime (zachování v2 dat + nové tabulky `family_link` a `pending_request`).
  - `Pulse4EmulatorUiTest.kt`: ověření navigace a terminologie (*Kruhy*, *Sdílení a oprávnění*), nepřítomnosti `xali.cz` a zobrazení notifikační lišty při nedostupném uzlu.

---

## 1. Sjednocení terminologie – Kruh
- Uživatelské označení **Skupina / Skupiny** bylo nahrazeno termínem **Kruh / Kruhy** napříč:
  - spodní navigací (`MainActivity.kt`: `"Kruhy"`),
  - přehledem na obrazovce Dnes (`OuraHorizonComponents.kt`: `"Kruhy & QR ›"`),
  - správou a detailem kruhů (`GroupsScreen.kt`),
  - nastavením (`SettingsHubScreen.kt`),
  - sdílenou validační logikou (`ui-logic/.../Sharing.kt`).
- Interní názvy tříd, databázových tabulek a protokolových struktur (`Group`, `GroupRow`, `GroupsScreen`) zůstaly zachovány kvůli zpětné kompatibilitě.

---

## 2. Rodinné vazby, přidávání dětí z kontaktů a zdrojové oprávnění
- **Doménový model (`sharing-policy/.../FamilyLink.kt`, `Grants.kt`):**
  - Přidán `FamilyLink` (`PENDING`, `VERIFIED`, `REJECTED`, `REVOKED`), `AgeProfile` (`ADULT`, `MINOR`) a `FamilyLinkPolicy`.
  - Potvrzení rodinné vazby **nikdy automaticky nevytváří `ShareGrant`**. Zdravotní data zůstávají nepřístupná až do udělení samostatného explicitního souhlasu.
  - Pro nezletilé (`AgeProfile.MINOR`) nestačí samostatný souhlas dítěte – `FamilyLinkPolicy.canActivateShareGrant` vyžaduje ověřenou vazbu na zákonného zástupce a explicitní spolupotvrzení (`guardianCoConsentAtMs`).
  - `ShareGrant` rozšířen o `sources: Set<Provider>?` (`null` pro zpětnou kompatibilitu starších grantů, jinak explicitní whitelist např. `Provider.WITHINGS_API` vs `Provider.GARMIN_PERSONAL`/`GARMIN_EXTENDED`). Filtrování je vynucováno přímo v `OwnerDayAssembler.assembleForGrant`, `Projector`, `ActivityProjector`, `ShareBuilder` a `ShareService`.
- **Protokol & DB (`sync-protocol/.../Envelope.kt`, `Database.kt`):**
  - Přidány typy obálek `FAMILY_LINK_REQUEST`, `FAMILY_LINK_RESPONSE`.
  - Room DB povýšena na verzi `3` s bezpečnou migrací `MIGRATION_2_3` (`family_link`, `pending_request`).
- **UI (`GroupsScreen.kt`):**
  - Tlačítko **„Přidat dítě“** s výběrem kontaktu, věkového profilu a explicitním upozorněním, že propojení nezpřístupňuje data.

---

## 3. Správa Kruhu více administrátory
- **Doménový model (`sharing-policy/.../Grants.kt`):**
  - `Group` rozšířen o `admins: Set<String>`, `pendingAdminHandover: AdminHandover?`, `dissolved: Boolean` a `effectiveAdmins`.
  - `CircleAdminPolicy` implementuje jmenování/odebrání správce (`promoteAdmin`, `demoteAdmin`), předání správy (`initiateHandover`, `acceptHandover`), bezpečné opuštění (`leaveCircle`) a zrušení kruhu (`dissolveCircle`).
  - Poslední správce nemůže kruh opustit, pokud v něm jsou další členové, dokud nástupce převzetí nepotvrdí.
- **Konzistence a offline/souběžné změny (`sync-protocol/.../Receiver.kt`):**
  - `BuiltInHandlers.membershipUpdate` ověřuje podpis vůči `effectiveAdmins` (nebo `pendingAdminHandover.targetMemberId`), zachovává kontrolu verze (`Stale`) a odmítá jakoukoliv aktualizaci, která by ne-zrušený kruh nechala bez správce.

---

## 4. Centrální obrazovka „Sdílení a oprávnění“
- Vytvořena nová obrazovka `SharingOverviewScreen.kt` (dostupná z `GroupsScreen` i `SettingsHubScreen` přes routu `"sharing_overview"`):
  1. **Sdílím s ostatními:** příjemci, kategorie, zdroj (`Withings` / `Garmin` / `Všechny zdroje`), kruh nebo přímá vazba, úprava a odvolání (`RevocationPlanDialog` s jasným vysvětlením zablokování fronty/budoucí synchronizace i omezení u již doručených kopií).
  2. **Ostatní sdílejí se mnou:** přijatá oprávnění a dostupné kategorie podle kontaktu.
  3. **Čekající žádosti:** žádosti o rodinné propojení, předání správy kruhu a členové čekající na souhlas (`MembershipPolicy.awaitingConsent`).

---

## 5. Reticulum UX: odstranění `xali.cz`, onboarding, QR a zjednodušené režimy
- **Odstranění výchozího uzlu (`Settings.kt`, `ReticulumBridge.kt`, `SettingsHubScreen.kt`, `ReticulumScreen.kt`):**
  - Odstraněno `rns.xali.cz`, `reticulum.betavine.net` i tiché fallbacky (`reticulum.hispagatos.org`, `relay.pulse4.app`). Nová instalace nekontaktuje žádný externí uzel bez volby uživatele.
- **Onboarding & veřejné uzly (`RnsOnboardingSheet.kt`):**
  - Průvodce prvním spuštěním: vlastní uzel, veřejný komunitní uzel (jasně označený jako *infrastruktura třetích stran – bez záruky dostupnosti*, s odkazy na `rmap.world` a `directory.rns.recipes` a ručním ověřením dostupnosti přes `probeNodeReachability`), QR import nebo lokální režim (`LOCAL_ONLY`).
- **QR sdílení konfigurace (`sync-protocol/.../RnsNodeQr.kt`, `RnsOnboardingSheet.kt`):**
  - Verzovaný JSON formát (`v = 1`), validace host/port/transport/propagationNode a striktní odmítání payloadů obsahujících citlivé klíče (`privateKey`, `seed`, `token`, `password`, `secret`).
- **Dvě úrovně rozhraní (`Settings.kt`, `SettingsHubScreen.kt`, `ReticulumScreen.kt`):**
  - **Běžný režim** (výchozí): srozumitelné pojmy (*Moje síťová identita*, *Připojovací uzel*, *Čekající zprávy*, *Uzel pro doručování zpráv*), stav připojení, QR tlačítka.
  - **Pokročilý režim**: plné RNS hashe, transportní režimy, editor `propagationNode`, diagnostika a detail fronty `outbox`.
