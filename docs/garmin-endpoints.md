# Garmin Personal API – specifikace endpointů (K DOPLNĚNÍ)

Vývoj probíhal v prostředí bez přístupu na `developer.garmin.com`, proto **nejsou v repozitáři žádné vymyšlené cesty**.
`app/src/main/assets/garmin_endpoints.json` obsahuje zástupné hodnoty `DOPLNIT` a u všech položek `"verified": false`.
Konektor (`GarminPersonalFetcher`) takovou položku **nikdy nezavolá** – oblast se v UI zobrazí jako „není nakonfigurováno“.

Pro každou metriku doplňte podle [oficiálního Personal Swaggeru](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)
a teprve potom přepněte `verified` na `true`:

| Pole v JSON | Co vyplnit |
|---|---|
| `path` | cesta endpointu a názvy query parametrů (`{from}`, `{to}` se nahradí daty ve formátu `dateFormat`) |
| `maxRangeDays` | nejdelší povolený rozsah jednoho požadavku (rozsahy se dělí podle specifikace endpointu) |
| `itemsPath`, `timePath`, `endTimePath`, `valuePath` | tečkované cesty v JSON odpovědi (`a.b.0.c`) |
| `timeFormat`, `scale` | formát času a násobek do vnitřní jednotky (`MetricType.unit`) |
| `localDatePath`, `zonePath`, `externalIdPath` | volitelné: den přiřazený poskytovatelem, časové pásmo, externí ID |
| `sampleRef` | odkaz na anonymizovanou vzorovou odpověď (uložte ji sem, např. `docs/samples/sleep.json`) |

Rovněž `token` (výměna osobního klíče za access token, platnost max. 24 h) vyžaduje ověřenou URL, tvar požadavku a cestu k tokenu.

U každé metriky musí být zdokumentováno: endpoint, cesta v JSON, jednotka, rozlišení a anonymizovaná vzorová odpověď.
Dokud to chybí, nelze tvrdit, že metrika je podporována; dostupnost polí a historie závisí na zařízení.
