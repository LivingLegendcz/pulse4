# Zásady ochrany osobních údajů – Pulse4

*English version: [PRIVACY.md](PRIVACY.md)*

*Stav: návrh ke zveřejnění. Poslední aktualizace: 20. listopadu 2026. Popisuje aplikaci ve verzi 0.2.3 a dále dětské funkce a schvalování rodičem z oddílu 8, které jsou označeny jako ve vývoji.*

Pulse4 je Android aplikace s důrazem na soukromí, která na jednom místě sbírá vaše sportovní a zdravotní údaje z Garminu, Withings a Health Connect. **Nemá vlastní server, uživatelské účty, reklamy ani analytiku.** Tyto zásady vysvětlují prostým jazykem, co aplikace s vašimi údaji dělá, a neslibují víc, než kolik skutečně dělá kód.

## 1. Stručně

- Vaše zdravotní a sportovní údaje jsou uložené **jen ve vašem telefonu**, v šifrované databázi. Vývojář je nevidí a nemá z nich žádnou kopii.
- Aplikace komunikuje přímo z telefonu s **Garminem** a **Withings** (s vaším vlastním účtem), aby stáhla vaše data, a se servery **mapových dlaždic**, když otevřete mapu trasy. Tyto třetí strany vidí technické údaje popsané v oddílu 5.
- Sdílení s rodinou nebo přáteli („okruhy“) je **dobrovolné, ve výchozím stavu vypnuté a probíhá přímo mezi telefony (P2P)**. Zprávy jsou šifrované end-to-end. Cokoli dostanou jen ty kontakty a jen ta datová pole, která vyberete.
- Aplikace **neobsahuje žádné reklamní, analytické, crash-reportovací ani sledovací komponenty** a data nikdy neprodává.
- U dětí vyžaduje sdílení i vstup do okruhů **schválení z telefonu rodiče** (oddíl 8, ve vývoji).
- Co už bylo doručeno do cizího telefonu, **nelze s jistotou vzít zpět** (oddíl 7).

## 2. Kdo aplikaci poskytuje a jak nás kontaktovat

Poskytovatel: **[JMÉNO A POŠTOVNÍ ADRESA POSKYTOVATELE]**

Kontakt pro dotazy k ochraně soukromí: **[CONTACT EMAIL]**

Protože Pulse4 nemá žádný backend, poskytovatel vaše zdravotní údaje nepřijímá, neukládá ani nezpracovává a nemůže je za vás vyhledat, upravit ani smazat. Dotazy k fungování aplikace nebo k těmto zásadám můžete poslat na výše uvedený kontakt.

## 3. Jaké údaje aplikace zpracovává

Vše níže uvedené se zpracovává ve vašem telefonu. Nic z této tabulky se neposílá poskytovateli.

| Údaje | Odkud pocházejí | Lze je sdílet s členy okruhu? |
|---|---|---|
| Složení těla: váha, tělesný tuk, beztuková hmota, svaly, kosti, voda v těle, viscerální tuk, cévní věk, rychlost pulzové vlny, bazální metabolismus, metabolický věk | Withings, Health Connect (jen váha a tuk), ruční zadání, import CSV | Jen váha a tělesný tuk |
| EKG a odvozené hodnoty (tep, QTc, QRS, klasifikace fibrilace síní) | Withings | Ne |
| Tep, klidový tep, variabilita tepu (HRV), dech, SpO2 | Garmin, Withings | Tep a HRV: ano. Ostatní: ne |
| Spánek (délka, fáze, skóre, zdřímnutí) | Garmin | Ano |
| Body Battery, stres, kroky | Garmin | Body Battery: ano. Stres a kroky: ne |
| Aktivity: sport, čas, vzdálenost, okruhy, grafy a **GPS trasy** | Garmin (včetně původních souborů FIT), import FIT | Ano, v několika úrovních detailu, volitelně se zónou soukromí, která skryje okolí místa, např. domova |
| Tréninkové plány a tréninky | Vytvořeny v aplikaci | Ano, s právem zobrazit, kopírovat, navrhovat změny nebo upravovat |
| Rodinné profily Withings (jména, data narození) a váš e-mail u Withings | Váš účet Withings | Ne (viz níže funkce váhy od rodiče k dítěti) |
| Vaše zobrazované jméno, vaše kontakty (jméno, veřejný klíč, adresa) a členství v okruzích | Zadáváte vy, výměna přes QR kód | Zobrazované jméno se sdílí s kontakty, se kterými se spárujete |
| Nastavení sdílení, rodinné vazby, schválení rodiče (oddíl 8) | Vznikají v aplikaci | Podle potřeby se předávají druhé straně |
| Přihlašovací údaje a relace Garmin a Withings | Zadáváte vy | Nikdy |

Aplikace **nežádá** o oprávnění k poloze a polohu telefonu nečte. GPS trasy pocházejí z vašich aktivit v Garminu. Oprávnění k poloze, která by mapová knihovna jinak přidala, jsou v manifestu aplikace výslovně odstraněna.

## 4. Kde jsou data uložena a jak jsou chráněna

- **Databáze.** Zdravotní údaje, nastavení, kontakty a záznamy o sdílení jsou v lokální databázi šifrované pomocí SQLCipher (AES-256). Klíč databáze tvoří 32 náhodných bajtů vygenerovaných ve vašem telefonu a zapečetěných klíčem v **Android Keystore** (podporuje-li to telefon, je podložený hardwarem).
- **Tajemství.** Tokeny nebo API klíč Garmin, relace Withings (cookies, e-mail, seznam rodinných profilů), volitelně zapamatované heslo Withings a soukromý klíč identity Reticulum jsou v odděleném úložišti, které je rovněž zapečetěno klíčem z Android Keystore. Nikdy se nezapisují do databáze, záložního souboru ani do logů.
- **Původní soubory aktivit.** Stažené soubory FIT z Garminu jsou v telefonu uložené jako jednotlivě šifrované soubory (zapečetěné klíčem z Keystore).
- **Mimo zálohy Androidu.** Aplikace nastavuje `allowBackup=false`, vypíná zálohování obsahu a ze zálohy do cloudu i z přenosu mezi zařízeními vylučuje všechny soubory, databáze a předvolby. Android tedy vaše data nekopíruje na Disk Google ani je nepřenáší do nového telefonu. Přechod na nový telefon vyžaduje vlastní šifrovanou zálohu aplikace (oddíl 9).
- **Síť.** Aplikace nepovoluje nešifrovaný (HTTP) provoz po internetu.

Šifrování chrání data ztraceného nebo vypnutého telefonu. Nechrání před někým, kdo telefon odemkne, před škodlivým softwarem běžícím pod vaším uživatelem ani před telefonem s root přístupem nebo jinak napadeným.

## 5. Co opouští váš telefon a komu se to posílá

### 5.1 Garmin

Pokud připojíte Garmin, aplikace kontaktuje servery Garminu přímo z telefonu s vašimi vlastními přihlašovacími údaji a stahuje vaše data (spánek, tep, HRV, Body Battery, stres, kroky, aktivity a původní soubory FIT). Existují dva způsoby připojení:

- **Osobní API klíč Garmin** (`personal.connectapi.garmin.com`): zadáte klíč, který vám vydal Garmin.
- **Přihlášení do Garmin Connect** (`sso.garmin.com`, `diauth.garmin.com`, `connectapi.garmin.com`): přihlásíte se e-mailem a heslem ke Garminu (a jednorázovým kódem, máte-li vícefázové ověření). Aplikace je použije jen k získání tokenů, heslo neukládá a tokeny drží v úložišti zapečetěném klíčem z Keystore. Stejný způsob se používá, když se rozhodnete odeslat tréninkový workout z Pulse4 do svého účtu Garmin, čímž se ve vašem účtu Garmin Connect vytvoří nebo upraví workout.

Garmin dostává požadavky, které aplikace posílá, vaši IP adresu a běžné technické údaje požadavku. Co s nimi Garmin dělá, upravují jeho vlastní zásady. Připojení ke Garminu využívá rozhraní, která Garmin pro aplikace třetích stran plně nedokumentuje. Pulse4 není s Garminem spojen ani jím schválen.

### 5.2 Withings

Pokud připojíte Withings, aplikace kontaktuje servery Withings přímo (`account.withings.com`, `scalews.withings.com`, `app.withings.com`; a `wbsapi.withings.net`, pokud použijete volitelnou cestu OAuth s vlastními vývojářskými přístupy) a stahuje měření včetně složení těla, tepu a EKG a seznam profilů na vašem účtu.

- Přihlašujete se e-mailovým kódem nebo e-mailem a heslem. Výsledné relační cookies a váš e-mail aplikace drží v úložišti zapečetěném klíčem z Keystore.
- Je-li zapnuto „Pamatovat heslo pro automatické obnovení“ (ve výchozím stavu je), heslo se rovněž drží v úložišti zapečetěném klíčem z Keystore jen v telefonu a slouží výhradně k obnovení relace, když vyprší (na pozadí nejvýše přibližně jednou za 6 hodin). Lze to vypnout v přihlašovacím dialogu; heslo se smaže při odpojení nebo když ho Withings odmítne.
- Účet Withings může obsahovat rodinné profily. U dětského profilu může rodič připojit druhou relaci a, pokud to zapne, aplikace přepošle váhu dítěte do jeho vlastní aplikace Pulse4 šifrovaným P2P kanálem (oddíl 6).

Withings dostává vaše požadavky, IP adresu a běžné technické údaje požadavku. Co s nimi Withings dělá, upravují jeho vlastní zásady. Připojení přes přihlášení e-mailem napodobuje webové přihlášení Withings a není oficiálně podporovanou integrací. Pulse4 není s Withings spojen ani jím schválen.

### 5.3 Health Connect

Pokud udělíte oprávnění, aplikace čte z Android Health Connect **váhu a tělesný tuk** (i na pozadí, pokud udělíte toto samostatné oprávnění). Do Health Connect nic nezapisuje. Data z Health Connect zůstávají v telefonu a nakládá se s nimi podle oddílu 4.

### 5.4 Poskytovatelé mapových dlaždic

Když otevřete mapu trasy, mapová knihovna stahuje dlaždice map přes internet. Server dlaždic vidí vaši **IP adresu**, technické údaje požadavku a **které dlaždice žádáte, z čehož lze poznat přibližnou oblast, kterou si prohlížíte**. Přesnou GPS trasu nedostane, ta se kreslí v telefonu. Poskytovatel závisí na stylu mapy zvoleném v nastavení:

| Styl mapy v aplikaci | Kontaktovaný server dlaždic |
|---|---|
| Dark Canvas (**výchozí**) | Esri / ArcGIS Online (`services.arcgisonline.com`) |
| Esri World Imagery (satelit) | Esri / ArcGIS Online (`server.arcgisonline.com`) |
| OpenStreetMap | servery dlaždic OpenStreetMap (`tile.openstreetmap.org`) |
| OpenTopoMap | OpenTopoMap (`*.tile.opentopomap.org`) |
| CyclOSM | CyclOSM (`*.tile-cyclosm.openstreetmap.fr`) |
| Vlastní MapLibre URL | Server, který zadáte. Necháte-li pole prázdné, dlaždice se berou z CARTO (`a.basemaps.cartocdn.com`) |
| Bez mapového podkladu (offline plátno) | Žádný. Trasa se kreslí bez jakéhokoli síťového požadavku |

Každý poskytovatel má vlastní zásady ochrany soukromí. Nechcete-li žádné mapové požadavky, zvolte „Bez mapového podkladu“.

### 5.5 Síť Reticulum / LXMF (P2P)

Sdílení mezi uživateli Pulse4 používá síť Reticulum a její zasílací vrstvu LXMF. Žádný centrální server Pulse4 neexistuje. V praxi to funguje takto:

- **Vaše adresa.** Telefon vytvoří dlouhodobou kryptografickou identitu. Vaše síťová adresa je odvozena z jejího veřejného klíče a neobsahuje telefonní číslo ani e-mail. Je-li telefon připojen k bráně, aplikace zveřejní oznámení (announce) s touto adresou a veřejným klíčem a s prázdnými aplikačními daty (vaše jméno v něm není). Lidé, se kterými se spárujete přes QR kód, dostanou navíc vaše zobrazované jméno.
- **V okolí (stejná Wi-Fi).** Pokud síť není nastavena na ruční režim, aplikace se na místní síti ohlašuje (typ služby `_pulse4._tcp`, obsahuje vaši pseudonymní adresu) a hledá další telefony s Pulse4. Toto ohlášení mohou vidět ostatní zařízení ve stejné Wi-Fi. Zprávy tak vyměňované jsou stejné end-to-end šifrované zprávy jako níže.
- **Brána (volitelná).** K dosažení kontaktů, které nejsou ve stejné Wi-Fi, se můžete připojit k bráně Reticulum (spojení TCP). **Žádná brána není přednastavená.** Průvodce při prvním spuštění nabízí krátký seznam veřejných uzlů (nyní Dublin Connect od projektu Reticulum a BetweenTheBorders), umožňuje zadat vlastní adresu, načíst konfigurační QR kód nebo pokračovat jen s místní Wi-Fi. Brány provozují třetí strany, ne poskytovatel Pulse4.
- **Propagation uzel (volitelný).** Aby se zprávy doručily, i když je příjemce offline, můžete zadat LXMF propagation uzel (schránku). **Žádný není přednastavený.** Zprávy tam čekají, dokud si je příjemce nevyzvedne. Aplikace se po přibližně 14 dnech vzdá uložené zprávy; pro kopii na uzlu platí vlastní pravidla uchovávání jeho provozovatele.
- **Co brána nebo propagation uzel vidí.** Vaši IP adresu, kdy a jak často se připojujete, pseudonymní cílovou adresu každé zprávy, přibližnou velikost a načasování. Vidí také oznámení (adresu a veřejný klíč), která jimi procházejí. **Obsah číst nemohou.** Obsah zpráv je šifrovaný end-to-end (E2EE v3: efemérní dohoda klíče X25519 pro každou zprávu a AES-256-GCM, odesílatel, příjemce a podpis jsou ukryti uvnitř šifrového textu a zprávy se doplňují na bloky po 256 bajtech) a vrstva Reticulum/LXMF přidává vlastní šifrování pro příjemce. Uzel může usuzovat na vzorce, například že dvě adresy spolu pravidelně komunikují. Totéž může zjistit každý, kdo ví, která adresa patří které osobě.
- **Příjemci.** Váš kontakt dostane přesně pole, která jste mu povolili, v nově sestavené očištěné podobě (ne jako kopii vašich záznamů). Trasy aktivit mohou skrýt zónu soukromí; přesný čas startu je samostatná volba.

Aplikaci můžete provozovat v režimu **jen místní síť** (bez připojení k bráně) nebo v plně ručním režimu.

### 5.6 Google Play a služby Google Play

Aplikace se distribuuje přes Google Play. Google zpracovává informace o instalaci, aktualizacích a (pokud jste to na zařízení povolili) o používání a diagnostice podle vlastních zásad; poskytovatel Pulse4 dostává jen souhrnné statistiky, které Google Play Console poskytuje vývojářům. Aplikace používá dvě komponenty Googlu:

- **Skener kódů Google** (služby Google Play) ke skenování párovacích QR kódů. Je to systémová komponenta a pro aplikaci nevyžaduje oprávnění k fotoaparátu.
- **Aktualizace v aplikaci Google Play** ke kontrole a instalaci nových verzí.

### 5.7 Co aplikace nedělá

- Nemá reklamy, analytiku ani službu pro hlášení pádů (žádný Firebase, Crashlytics ani podobné) a nic nehlásí poskytovateli.
- Data neprodává, nepronajímá ani s nimi neobchoduje.
- Nepoužívá vaši polohu a nežádá o oprávnění k poloze.
- Nespojuje data z různých služeb do profilu uloženého kdekoli mimo váš telefon.

## 6. Sdílení s rodinou a okruhy

- **Ve výchozím stavu vypnuto.** Nic se nesdílí, dokud nevytvoříte oprávnění ke sdílení. Vybíráte příjemce, datová pole (například jen délku spánku, nebo spánek s fázemi), časový rozsah (jednorázově, od data, nebo průběžně od dneška) a u aktivit úroveň detailu, zónu soukromí a zda se zahrne přesný čas startu. Průběžné sdílení nikdy neodhalí historii z doby před jeho vytvořením.
- **Okruhy.** Okruh je pojmenovaná skupina se správcem. Členství v okruhu samo nikomu nedává přístup k vašim datům. Ten dává jen oprávnění, které vás uvádí jako příjemce. Zprávy o členství v okruhu se vyměňují mezi telefony členů.
- **Váha od rodiče k dítěti.** Pokud rodič připojil dětský profil Withings a zapnul přeposílání, váha tohoto dítěte se pošle do telefonu dítěte stejným šifrovaným kanálem. Telefon dítěte ji uloží jen tehdy, když si dítě příjem zapnulo.
- **Tréninkové plány.** Sdílení plánu umožní příjemci plán zobrazit, kopírovat, navrhovat změny nebo ho upravovat podle práva, které mu dáte.
- **Zúžení platí okamžitě.** Sdílení můžete kdykoli zastavit nebo omezit. Zprávy zařazené do fronty pro zastavené oprávnění se zruší dřív, než telefon opustí.

## 7. Meze vzetí dat zpět

Když přestanete sdílet, odeberete někoho z okruhu nebo (u dítěte) rodič schválení odvolá, aplikace přestane posílat, zruší, co čeká ve frontě, a požádá aplikaci příjemce o odstranění dat, která už dostala. Mějte na paměti:

- Data **už doručená** do telefonu příjemce nelze násilím vymazat. Žádost o jejich odstranění provádí aplikace příjemce a nelze ji vynutit. Příjemce také mohl to, co viděl, zkopírovat, vyfotografovat nebo pořídit snímek obrazovky.
- Příjemce, který je **offline**, se o změně dozví až při dalším připojení.
- Kopie nedoručené zprávy může zůstat na **propagation uzlu** provozovaném třetí stranou, dokud nevyprší nebo si ji nikdo nevyzvedne.
- Aplikace vás na tyto meze upozorňuje tam, kde sdílení odvoláváte.

## 8. Děti a schvalování rodičem

*Tento oddíl platí od verze [X.Y] (ve vývoji) a popisuje zamýšlené chování. Dokud tato verze nevyjde, aplikace se na věk ještě neptá a schválení rodičem nevynucuje.*

Pulse4 je navržen tak, aby ho mohly s účastí rodiče používat i děti od 6 let. Aplikace uplatňuje tato pravidla.

**Otázka na věk.** Při prvním otevření po instalaci se aplikace zeptá, zda je uživatel dítě (do 18 let), nebo dospělý. Jde o **vlastní prohlášení**. Aplikace věk nemůže ověřit a žádná informace o něm se poskytovateli neposílá.

**Dospělý.** Aplikace funguje normálně.

**Dítě.** Aplikace tento telefon okamžitě označí jako dětský. Prvním povinným krokem je **přidat rodiče**: dítě se spáruje s telefonem rodiče (přes QR kód) a odešle žádost o rodinnou vazbu. **Dokud rodič žádost na svém zařízení nepřijme, je aplikace zablokovaná.** V tomto stavu může dítě jen párovat, odeslat nebo znovu odeslat žádost, případně ji zrušit. Dítě se nemůže přihlásit ke Garminu ani Withings, vidět metriky ani vstupovat do okruhů.

**Více rodičů nebo zákonných zástupců.** K dítěti může být připojeno více rodičů či zástupců (například oba rodiče; aplikace stanoví nevelký horní limit). Aplikace se odemkne, jakmile žádost přijme **jeden** zástupce. Přidání dalšího zástupce později vyžaduje dvě věci: nový zástupce přijme žádost dítěte **a** stávající zástupce přidání této osoby schválí podepsaným schválením. Tím se brání tomu, aby si dítě samo jmenovalo „zástupce“, který by mu věci schvaloval. Každá žádost o schválení se posílá **všem** připojeným zástupcům. **Kterýkoli jeden** z nich ji může schválit nebo zamítnout. Platí první platné rozhodnutí; ostatní zástupci pak vidí, že už bylo rozhodnuto, a kým.

**Rodič schvaluje každý krok zvlášť.** Po přijetí vazby:

- Každý **okruh, do kterého chce dítě vstoupit nebo ho vytvořit**, je samostatná žádost, kterou rodič schválí nebo zamítne. Rodič vidí název okruhu a jeho správce a o co se žádá.
- Každé **nové nebo rozšířené sdílení dat** (nový příjemce, další datová pole, další práva k plánům, širší časový rozsah nebo nový zdroj dat) je samostatná žádost, kterou rodič schválí nebo zamítne. Rodič vidí příjemce, pole a rozsah v porovnání s tím, co bylo schváleno dříve.
- Telefon dítěte neodešle zprávy o členství v okruhu ani nezahájí sdílení, dokud nedorazí schválení.
- **Zúžení nebo vypnutí sdílení schválení nikdy nevyžaduje.**

**Jak se schválení ověřuje.** Schválení je digitálně podepsaná zpráva vytvořená v telefonu rodiče jeho soukromým klíčem. Telefon dítěte ověří podpis proti veřejnému klíči rodiče (tomu, který dostal při vzniku vazby), zkontroluje, že schválení odpovídá jeho vlastní čekající žádosti, nevypršelo, nebylo už použito a přesně odpovídá tomu, oč se žádalo. Každé schválení lze použít jednou a má dobu platnosti. Schválení putuje stejným end-to-end šifrovaným kanálem.

**Rodič může kdykoli odvolat.** Kterýkoli připojený zástupce může odvolat konkrétní schválení sdílení dat, schválení okruhu (telefon dítěte pak z okruhu odejde a zruší své sdílení do něj) nebo svou vlastní rodinnou vazbu, a to včetně schválení, která dal jiný zástupce. Zástupce může také odebrat vazbu jiného zástupce (například byla-li vytvořena omylem nebo jí už nedůvěřuje). Odvolání je podepsané a telefon dítěte ho použije hned po přijetí. Schválení zůstávají platná, dokud je připojen alespoň jeden zástupce. Když je odebrána **poslední** vazba zástupce, všechna schválení se odvolají a aplikace se vrátí do zablokovaného stavu „čekání na rodiče“. Vazbu může ze své strany odebrat i dítě, se stejným účinkem. Přechod z dětského stavu na dospělý v tomto telefonu je možný jen s podepsaným uvolněním od připojeného zástupce.

**Co se ukládá a kde.** Prohlášení o věku, rodinná vazba a schválení jsou uložené **jen v telefonu, v šifrované databázi**. **Nejsou součástí zálohy aplikace** a neposílají se poskytovateli. Ostatním stranám vazby jsou viditelné jen v rozsahu nutném k fungování (například každý zástupce vidí žádosti dítěte a rozhodnutí a schválení ostatních zástupců).

**Meze, o kterých byste měli vědět.**

- Vynucování probíhá **na zařízeních**. Neexistuje server, který by ho mohl ověřit nebo vynutit. Kdo má nad telefonem plnou kontrolu (například telefon s root přístupem nebo upravená aplikace), může je obejít.
- **Vymazání dat aplikace nebo přeinstalace vrátí otázku na věk na začátek.** Dítě, které to udělá a odpoví „dospělý“, v tomto telefonu omezení odstraní. Rodiče by o tom měli vědět.
- Aplikace nepozná, zda osoba, která vazbu přijímá, je opravdu rodič nebo zákonný zástupce dítěte. Rodiče by se měli se svým dítětem párovat osobně. Schvalovat může kterýkoli jeden připojený zástupce, proto by každý zástupce měl být někdo, komu důvěřujete.
- Data **už doručená** do jiných telefonů nelze vždy vzít zpět (oddíl 7).
- Máte-li dětský profil u Withings nebo Garminu, ty služby mají vlastní věková pravidla a podmínky. Rozhodnutí, zda je připojit, je na rodičích.

## 9. Záloha a export

Aplikace umí vyexportovat **šifrovaný záložní soubor**, který si sami vytvoříte a uložíte pomocí systémového výběru souborů na libovolné místo.

- Obsahuje: vaše měření (observace), aktivity, GPS trasy a tréninkové plány.
- **Neobsahuje:** tokeny ani hesla Garmin či Withings, soukromou identitu Reticulum, kontakty, oprávnění ke sdílení, rodinné vazby, schválení rodiče ani prohlášení o věku.
- Je šifrovaný (AES-256-GCM) klíčem odvozeným z vámi zvoleného hesla (alespoň 8 znaků) pomocí PBKDF2. Když heslo ztratíte, soubor nelze otevřít. Poskytovatel s obnovou pomoci nemůže.
- Obnova v novém telefonu vytvoří novou síťovou identitu: kontakty a sdílení je třeba nastavit znovu a dítě musí párování s rodičem projít znovu.
- Uložíte-li soubor do složky synchronizované s cloudem, bude (šifrovaný) soubor u poskytovatele té služby.

## 10. Protokolování

- **Vydané sestavení** (verze z Google Play) protokoluje na minimum: informační a ladicí zprávy jsou potlačeny, varování a chyby se zbavují e-mailových adres, tokenů, cookies, identifikátorů relace, hesel a názvů hostitelů s porty, zkracují se a nemají výpisy zásobníku. Logy zůstávají v telefonu v systémovém logu Androidu a aplikace je nikam neposílá.
- **Ladicí sestavení (debug)** (vývojářské verze, které se nedistribuují přes Google Play) protokolují podrobněji bez tohoto čištění a obsahují přijímač vývojářských příkazů. Ladicí sestavení s reálnými daty nepoužívejte v telefonu, který sdílíte s ostatními.
- Diagnostická obrazovka přihlášení Withings ukazuje očištěný záznam posledních pokusů o přihlášení (nejvýše 30 záznamů). Je uložen lokálně a ven se dostane, jen když stisknete tlačítko kopírovat.

## 11. Uchovávání a mazání

Poskytovatel o vás žádná data nedrží, takže uchovávání máte ve svých rukou.

| Co uděláte | Co se stane |
|---|---|
| **Odpojíte Garmin nebo Withings** v aplikaci | Příslušné tokeny, relace a zapamatované heslo se z telefonu smažou. Už stažená měření v aplikaci zůstanou. |
| **Odeberete dětský profil / odvoláte sdílení / opustíte okruh** | Přeposílání nebo sdílení se zastaví. Co nelze vzít zpět, viz oddíl 7. |
| **Vymažete úložiště aplikace** v nastavení Androidu nebo aplikaci **odinstalujete** | Databáze, tajemství, původní soubory aktivit, nastavení, klíč identity, prohlášení o věku a schválení se z telefonu smažou. Data už doručená do jiných telefonů a data u Garminu, Withings, uzlů a poskytovatelů map to neovlivní. |
| **Smažete záložní soubor** | Smažete ho sami tam, kam jste ho uložili. Aplikace na něj potom nedosáhne. |

Data odeslaná na propagation uzel uchovává tento uzel podle pravidel jeho provozovatele. Aplikace přestane opakovat nedoručené zprávy po omezené době (asi 7 dní u nahrazených souhrnů a 14 dní u zpráv uložených na propagation uzlu).

## 12. Vaše práva

Podle toho, kde žijete, můžete mít k osobním údajům práva, například na přístup, opravu, výmaz, omezení zpracování, přenositelnost a námitku, a právo podat stížnost u místního úřadu pro ochranu osobních údajů. Protože poskytovatel kopii vašich dat nemá, uplatňují se tato práva hlavně **lokálně a u služeb, které sami používáte**:

- **Přístup.** Vše, co o vás aplikace drží, vidíte v aplikaci. Šifrovaná záloha umožňuje exportovat vaše měření, aktivity a plány (oddíl 9).
- **Oprava.** Údaje opravte u zdroje (Garmin, Withings) nebo zadáním nových hodnot. Měření z těchto služeb se ze zdroje stahují znovu.
- **Výmaz.** Odpojte účty, odvolejte sdílení a pak vymažte úložiště aplikace nebo ji odinstalujte (oddíl 11).
- **Přenositelnost.** Šifrovaná záloha je vlastní kontejner aplikace, určený k přenosu mezi telefony s Pulse4. Není to univerzální exportní formát.
- **Odvolání souhlasu.** Odpojení účtu, vypnutí zdroje dat nebo odvolání sdílení okamžitě zastaví další zpracování.
- **Třetí strany.** U dat, která drží Garmin, Withings, poskytovatelé map, provozovatelé uzlů nebo lidé, se kterými jste sdíleli, uplatňujte práva u nich.
- **Rodiče a děti.** Rodič může schválení kdykoli odvolat (oddíl 8).

Napíšete-li na kontakt z oddílu 2, poskytovatel vám sdělí, co může a nemůže udělat. Údaje, které nemá, vyhledat nemůže.

## 13. Oprávnění, která aplikace používá

| Oprávnění | Proč |
|---|---|
| Internet, stav sítě | Stahování z Garminu a Withings, mapové dlaždice, brány Reticulum |
| Stav Wi-Fi, multicast, místní síť | Hledání dalších telefonů s Pulse4 ve stejné Wi-Fi |
| Čtení Health Connect: váha, tělesný tuk, čtení na pozadí | Volitelný import váhy a tělesného tuku |
| Oznámení | Stav synchronizace a upozornění na příchozí sdílení |
| Služba v popředí (zasílání zpráv), spuštění po startu, ignorování optimalizace baterie | Udržení P2P spojení, aby sdílená data dorazila včas. Lze vypnout v nastavení aplikace |

O polohu, fotoaparát, mikrofon, kontakty a celoplošný přístup k úložišti se **nežádá**.

## 14. Zabezpečení

Kromě výše uvedené ochrany se aplikace snaží udržet P2P zprávy ověřené (kontrola odesílatele, ověření otisku obsahu, odmítání opakovaných zpráv). Žádný systém není dokonale bezpečný. Udržujte telefon aktualizovaný a zamčený, zvolte silné heslo zálohy a zranitelnosti hlaste na kontakt z oddílu 2.

## 15. Změny těchto zásad

Když se aplikace změní způsobem, který se týká vašich údajů, zásady se aktualizují a změní se datum poslední aktualizace. Podstatné změny budou zmíněny i v poznámkách k vydání. Dřívější verze najdete v historii repozitáře projektu.

## 16. Názvy třetích stran

Garmin, Withings, Google, Esri, OpenStreetMap, OpenTopoMap, CyclOSM, CARTO a Reticulum jsou majetkem svých vlastníků. Pulse4 je nezávislý projekt a není s nimi spojen ani jimi schválen.

---

## Poznámky k nasazení (nejsou součástí zásad)

- Google Play vyžaduje **veřejnou, needitovatelnou URL** zásad ochrany soukromí, dostupnou bez přihlášení, a tytéž zásady musí být odkazované i v aplikaci. Tento soubor (i anglickou verzi) zveřejněte přes **GitHub Pages**, nebo jako URL použijte zobrazení souboru v repozitáři. Stabilní vykreslená stránka je lepší než surový Markdown.
- Před zveřejněním nahraďte zástupné texty `[JMÉNO A POŠTOVNÍ ADRESA POSKYTOVATELE]`, `[CONTACT EMAIL]` a `[X.Y]`.
- Oddíl 8 popisuje funkci ve vývoji. Nezveřejňujte ho jako současné chování, dokud nevyjde verze, která ji obsahuje, a každé tvrzení znovu ověřte proti vydanému kódu.
- Položky, které je třeba ještě ověřit proti oficiálním zdrojům, jsou v [PRIVACY_OPEN_ITEMS.md](PRIVACY_OPEN_ITEMS.md). Nepopisujte tyto zásady jako vyhovující jakémukoli zákonu či pravidlům obchodu, dokud nejsou vyřešeny.
- Odpovědi ve formuláři „Bezpečnost údajů“ (Data safety) v Play Console udržujte v souladu s tímto dokumentem.
