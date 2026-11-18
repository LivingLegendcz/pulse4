# Zásady ochrany osobních údajů – Pulse4 (beta)

*Platné od 14. 11. 2026*

## Česky

**Pulse4 nemá žádný vlastní server.** Všechna data zůstávají ve vašem telefonu.

### Jaká data aplikace zpracovává
| Data | Odkud | Kde jsou uložena |
|---|---|---|
| Zdravotní a sportovní data (váha, složení těla, tep, HRV, spánek, aktivity, GPS trasy) | Garmin, Withings, Health Connect, ruční zadání, import FIT/CSV | Šifrovaná databáze v telefonu |
| Přihlašovací relace Withings (cookies důvěryhodného zařízení, e-mail, seznam rodinných profilů se jménem a datem narození) | Po přihlášení v nativním dialogu | Šifrované úložiště Android Keystore v telefonu; **heslo se nikdy neukládá** |
| Tokeny / API klíč Garmin | Zadáte vy | Šifrované úložiště Android Keystore v telefonu |

### Komu se data posílají
- **Garmin a Withings** – pouze za účelem stažení *vašich* dat z *vašeho* účtu, přímo z telefonu na jejich servery.
- **Health Connect** – aplikace pouze čte váhu a tělesný tuk, nic nezapisuje.
- **Sdílení se skupinou / rodinou (P2P)** – jen pokud ho sami zapnete a jen vybraným kontaktům; přenos je end-to-end šifrovaný. Přeposílání dětského profilu Withings přes P2P probíhá pouze tehdy, když ho rodič výslovně nastaví.
- Volitelné mapové dlaždice – jen pokud zadáte zdroj map; poskytovatel map vidí oblast, kterou zobrazujete.

Aplikace **neobsahuje reklamy, analytiku ani sledovací nástroje** a data nikomu neprodává.

### Záloha a smazání
Data nejsou součástí záloh Androidu. Smažete je odpojením účtů v aplikaci, vymazáním dat aplikace nebo jejím odinstalováním.

### Neoficiální rozhraní
Propojení s Garmin a Withings využívá rozhraní, která tito výrobci veřejně nedokumentují. Pulse4 s nimi není spojen ani jimi schválen; propojení může kdykoli přestat fungovat.

### Kontakt
pepe.kocourek@gmail.com

---

## English

**Pulse4 has no server of its own.** All data stays on your phone.

- **Health & fitness data** (weight, body composition, heart rate, HRV, sleep, activities, GPS tracks) from Garmin, Withings, Health Connect, manual entry or FIT/CSV import are stored in an encrypted on-device database.
- **Withings session**: your password is **never stored** (used once during login only). Only encrypted trusted-device cookies, your e-mail, and family profile metadata (names, birthdates) are stored on the device using Android Keystore. **Garmin tokens** are stored encrypted on the device and used solely to download *your* data directly from Garmin/Withings.
- **Health Connect**: read-only access to weight and body fat.
- **Group / family sharing (P2P)** happens only if you enable it (including explicit parent opt-in for child profile forwarding), only with contacts you choose, end-to-end encrypted.
- **No ads, no analytics, no trackers.** Data is never sold or shared with third parties.
- Data is excluded from Android backups; delete it by disconnecting accounts, clearing app data or uninstalling.
- Garmin and Withings integrations use undocumented interfaces (with risk of temporary lockouts or breaking changes); Pulse4 is not affiliated with or endorsed by them.

Contact: pepe.kocourek@gmail.com
