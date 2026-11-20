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

## Daily metrics: primary paths, fallback and sample provenance

`garmin_endpoints.json` may list several entries for one metric. Entries without `"fallback": true` are tried first; an entry with
`"fallback": true` is called only when the primary entries failed (403/5xx/not configured) or returned nothing mappable for the whole
fetched window (404/204/empty body). RESTING_HEART_RATE, STEPS and STRESS use the paths of v0.1.x as primary
(`/wellness-service/wellness/dailyHeartRate`, `/usersummary-service/stats/steps/daily`, `/usersummary-service/stats/stress/daily`) and
`/usersummary-service/v2/dailysummaries` as fallback. BODY_BATTERY stays on `dailysummaries` (`bodyBattery.latestValue`, the level);
the old `bodyBattery/reports/daily` endpoint only exposes `charged`, a different quantity.

Both paths of a metric map to the same canonical `ImportKey` (`metric`, `localDate`), so a day is stored once whichever endpoint answered.

`docs/samples/heartrate.json`, `steps.json` and `stress.json` are **synthetic, anonymised fixtures** written from the official OpenAPI
schema (`docs/garmin_personal_api_openapi.json`) and the field paths of v0.1.x. They are not captures of a real Garmin response; the
real layout of these endpoints is unverified in the repository. `dailysummaries.json` follows the same schema.

Diagnostics: a metric that fails while others of its area succeed is reported as `Partial:METRIC=Code` in `area_state.lastError`
(the area is still saved). `EmptyResponse` is reported only when the whole fetched window of a metric is empty, the metric has stored
history and its newest stored observation is older than 7 days (`GarminPersonalFetcher.STALE_EMPTY_MS`). An ordinary empty day, or
an empty window for a metric that never had data, is not an error.
