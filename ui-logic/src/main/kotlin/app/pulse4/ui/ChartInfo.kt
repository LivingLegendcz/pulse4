package app.pulse4.ui

import app.pulse4.domain.MetricType

/**
 * Explanations for the (i) button of charts: how to read the view (series, axes, window, aggregation,
 * gestures, gaps, data source). Texts only describe what the code draws; they never add medical thresholds
 * or interpretations. Metric/physiology explanations live in the existing metric / telemetry info dialogs.
 */
enum class ChartKey {
    SINGLESTAT_SPARKLINE,
    METRIC_HISTORY_LINE,
    METRICS_CORRELATION,
    METRICS_CORRELATION_NORMALIZED,
    WEIGHT_COMPOSITION_STACKED_AREA,
    BODY_COMPOSITION_BAR,
    SEGMENTAL_TABLE,
    SEGMENTAL_COMPARE,
    SEGMENTAL_SINGLE,
    SLEEP_STAGES_BAR,
    ECG_STRIP,
    OURA_24H_HORIZON,
    ACTIVITY_TELEMETRY_CURVES,
    ACTIVITY_COMPARE_CHANNEL,
    ACTIVITY_COMPARE_ROUTES,
    GROUP_COMPARE,
}

/** One legend entry of a chart. [id] links the entry to its color / line style on the UI side. */
data class ChartInfoSeries(
    val id: String,
    val label: String,
    val unit: String = "",
    val detail: String? = null,
) {
    fun line(): String = buildString {
        append(label)
        if (unit.isNotBlank()) append(" (").append(unit).append(")")
        if (!detail.isNullOrBlank()) append(" – ").append(detail)
    }
}

data class ChartInfo(
    val key: ChartKey,
    val title: String,
    val intro: String,
    val seriesHeading: String?,
    val series: List<ChartInfoSeries>,
    val bullets: List<String>,
) {
    /** Whole dialog as text (tests, accessibility, logs). */
    fun plainText(): String = buildList {
        add(title)
        add(intro)
        if (series.isNotEmpty()) {
            seriesHeading?.let { add(it) }
            series.forEach { add("• " + it.line()) }
        }
        bullets.forEach { add("• $it") }
    }.joinToString("\n")
}

/** Per-chart context that changes the wording (all optional). */
data class ChartInfoOptions(
    val unit: String = "",
    /** e.g. "14d" for the Today sparkline window. */
    val windowLabel: String? = null,
    val hasBand: Boolean = false,
    val hasSecondary: Boolean = false,
    val secondaryLabel: String = "",
    val isScatter: Boolean = false,
    val metric: MetricType? = null,
)

object ChartInfoTexts {

    const val DIALOG_CLOSE = "Rozumím"
    const val BUTTON_DESCRIPTION = "Vysvětlení grafu"

    /** How a daily value is formed for a metric, derived from [Aggregation] (single source of truth). */
    fun aggregationPhrase(type: MetricType): String = when (Aggregation.of(type)) {
        Agg.SUM -> "součet za den"
        Agg.LAST -> "poslední měření dne"
        Agg.AVG -> "průměr dne"
    }

    /** Generic description of daily aggregation for charts that are not bound to one metric. */
    val DAILY_VALUE_RULE: String =
        "Denní hodnota: u kroků a délky spánku součet za den, u váhy, složení těla, Body Battery, klidového tepu " +
            "a skóre spánku poslední měření dne, u ostatních metrik průměr dne."

    private const val GAPS_LINE =
        "Dny bez měření se v křivce přeskočí a čára spojí sousední naměřené body; chybějící hodnota není nula."

    private const val USUAL_BAND =
        "Světlé pásmo je obvyklé rozmezí P25–P75 – dolní a horní kvartil vašich vlastních hodnot za zobrazené období " +
            "(vyžaduje alespoň 7 hodnot, jinak se nezobrazí). Je to popis vašich dat, ne zdravotní norma."

    /** Legend entry for a series whose [ChartInfoSeries.id] is a [MetricType] name: adds the daily aggregation rule. */
    fun metricSeries(id: String, label: String, unit: String): ChartInfoSeries {
        val type = MetricType.entries.firstOrNull { it.name == id }
        return ChartInfoSeries(id, label, unit, type?.let { "denní hodnota: ${aggregationPhrase(it)}" })
    }

    fun build(
        key: ChartKey,
        series: List<ChartInfoSeries> = emptyList(),
        options: ChartInfoOptions = ChartInfoOptions(),
    ): ChartInfo = when (key) {
        ChartKey.SINGLESTAT_SPARKLINE -> singlestat(options)
        ChartKey.METRIC_HISTORY_LINE -> metricLine(options)
        ChartKey.METRICS_CORRELATION -> multiSeries(
            key, "Společný korelační graf",
            "Graf překrývá vybrané metriky za zvolené období, aby šlo porovnat jejich vývoj. Každá řada je jedna metrika, " +
                "bod je jedna denní hodnota z měření uložených v aplikaci.",
            series,
            listOf(DAILY_VALUE_RULE),
        )
        ChartKey.METRICS_CORRELATION_NORMALIZED -> multiSeries(
            key, "Korelační překryv (0–100 %)",
            "Graf překrývá vybrané metriky za zvolené období. V relativním režimu je každá řada přepočtena na 0–100 % " +
                "svého vlastního rozpětí: 0 % je minimum řady v zobrazeném období, 100 % její maximum.",
            series,
            listOf(
                "Ukazuje jen tvar a načasování vývoje. Nelze z něj číst skutečné hodnoty ani porovnávat velikost změn " +
                    "mezi metrikami; hodnoty ve štítcích jsou v tomto režimu také relativní (%).",
                "Skutečné hodnoty najdete v detailních kartách metrik pod grafem nebo po přepnutí na fyzikální jednotky.",
                DAILY_VALUE_RULE,
            ),
        )
        ChartKey.SEGMENTAL_COMPARE -> multiSeries(
            key, "Porovnání částí těla",
            "Graf porovnává vývoj svalové nebo tukové hmoty v jednotlivých částech těla (ruce, trup, nohy) mezi měřeními " +
                "Withings Body Scan. Každá řada je jedna část těla.",
            series,
            listOf(
                "Hodnota, která se od mediánu dané části v zobrazeném období liší o více než 45 % (mimo 55–145 % mediánu), " +
                    "se jako pravděpodobná chyba měření z grafu vynechá.",
                if (options.unit == "%") {
                    "Jednotka %: podíl vybrané tkáně z hmotnosti dané části těla."
                } else {
                    "Hodnoty jsou hmotnost dané tkáně v nastavené jednotce hmotnosti."
                },
                "Přepínač Svaly / Tuk a jednotek je v kartě Segmentální rozložení nad grafem.",
            ),
        )
        ChartKey.GROUP_COMPARE -> multiSeries(
            key, "Porovnání členů kruhu",
            "Každá řada je jeden člen kruhu ve zvolené metrice a časovém okně (7, 14 nebo 30 dní). Bod je jedna denní hodnota.",
            series,
            listOf(
                "Vaše řada („Já“) se počítá z vašich lokálních měření; ostatní členové z denních dat, která s vámi sdílejí " +
                    "přes P2P. Co člen nesdílí nebo dosud nedoručil, v grafu chybí (mezera, ne nula).",
                "Barvy řad jsou pevně přiřazeny podle pořadí členů a stejné barvy mají karty žebříčku pod grafem.",
                "Žebříček pod grafem řadí členy podle poslední zobrazené hodnoty (u klidového tepu od nejnižší).",
            ),
        )
        ChartKey.ACTIVITY_COMPARE_CHANNEL -> multiSeries(
            key, "Porovnání kanálu mezi aktivitami",
            "Graf překrývá jeden vybraný kanál záznamu (např. tep nebo tempo) u porovnávaných aktivit. Každá řada je jedna aktivita; " +
                "chip nad grafem mění kanál.",
            series,
            listOf(
                "Osa X je relativní průběh aktivity: každá aktivita je rozdělena do 100 stejně dlouhých úseků podle času " +
                    "a hodnota úseku je průměr kanálu. Aktivity různé délky se tedy překrývají podle postupu, ne podle uplynulého času.",
                "Aktivita bez zvoleného kanálu v záznamu se v grafu neobjeví.",
            ),
        )
        ChartKey.WEIGHT_COMPOSITION_STACKED_AREA -> weightComposition(series, options)
        ChartKey.BODY_COMPOSITION_BAR -> bodyBar(series)
        ChartKey.SEGMENTAL_TABLE -> segmentalTable()
        ChartKey.SEGMENTAL_SINGLE -> ChartInfo(
            key, "Vývoj vybrané části těla",
            "Křivka ukazuje vývoj svalové nebo tukové hmoty jedné vybrané části těla mezi měřeními Withings Body Scan.",
            null, emptyList(),
            listOf(
                "Hodnota, která se od mediánu dané části v zobrazeném období liší o více než 45 % (mimo 55–145 % mediánu), " +
                    "se jako pravděpodobná chyba měření vynechá.",
                "Jednotku (hmotnost / podíl v dané části) a tkáň (svaly / tuk) volíte přepínači v kartě nad grafem; " +
                    "část těla měníte klepnutím na řádek tabulky nebo na čip.",
                "Klepnutím nebo tažením po grafu vyberete měření; datum je vidět v hlavičce.",
                GAPS_LINE,
            ),
        )
        ChartKey.SLEEP_STAGES_BAR -> sleepStages(series)
        ChartKey.ECG_STRIP -> ChartInfo(
            key, "Orientační EKG křivka",
            "Pruh je ilustrativní schéma, ne váš zaznamenaný signál: aplikace kreslí opakující se typický komplex P‑Q‑R‑S‑T.",
            null, emptyList(),
            listOf(
                "Tvar křivky ani mřížka nevycházejí z naměřených dat a mřížka nemá kalibrované měřítko (mV, s).",
                "Neslouží k hodnocení rytmu ani tvaru vlny. Údaje z vašeho měření (tep, QTc, klasifikace) jsou v kartě nad pruhem.",
                "Měření pochází z madla váhy Withings Body Scan.",
            ),
        )
        ChartKey.OURA_24H_HORIZON -> ChartInfo(
            key, "24h biometrický horizont",
            "Pruh je schematický rozvrh dne od 00:00 do 24:00 rozdělený na čtyři bloky. Bloky mají pevnou délku " +
                "a nejsou vypočteny z vašich měření.",
            "Bloky pruhu",
            listOf(
                ChartInfoSeries("sleep", "Spánek", detail = "00:00–07:00"),
                ChartInfoSeries("hrv", "HRV vrchol", detail = "07:00–12:00"),
                ChartInfoSeries("activity", "Aktivita", detail = "12:00–18:00"),
                ChartInfoSeries("wind_down", "Zklidnění", detail = "18:00–24:00"),
            ),
            listOf("Bílá svislá čára značí aktuální hodinu dne."),
        )
        ChartKey.ACTIVITY_TELEMETRY_CURVES -> telemetry(series)
        ChartKey.ACTIVITY_COMPARE_ROUTES -> ChartInfo(
            key, "Překryv GPS tras",
            "Mapa překrývá GPS trasy porovnávaných aktivit. Každá aktivita je jedna barevná čára.",
            "Trasy",
            series,
            listOf(
                "Barva trasy odpovídá barvě tečky u názvu aktivity v tabulce porovnání a v grafu kanálu.",
                "Aktivita bez alespoň dvou bodů s polohou se v mapě nezobrazí.",
                "Použitý podklad mapy je uveden v názvu karty; v režimu Offline se trasy vykreslí bez mapového podkladu.",
            ),
        )
    }

    private fun singlestat(o: ChartInfoOptions): ChartInfo = ChartInfo(
        ChartKey.SINGLESTAT_SPARKLINE, "Křivka na kartě metriky",
        "Velké číslo je poslední hodnota metriky. Křivka na pozadí karty ukazuje její vývoj" +
            (o.windowLabel?.let { " za posledních $it" } ?: " za zvolené období") +
            ", jeden bod je jedna denní hodnota.",
        null, emptyList(),
        listOf(
            "Osa Y není od nuly: křivka je roztažená mezi minimum a maximum období (uvedené vpravo dole jako Min · Max), " +
                "ukazuje tedy tvar vývoje, ne velikost hodnot.",
            "Svítící bod na konci křivky je poslední dostupná hodnota.",
            "Odchylka vedle hodnoty („vs medián“) srovnává poslední hodnotu s mediánem období; medián se počítá, " +
                "jen když je v období alespoň 7 hodnot.",
            DAILY_VALUE_RULE,
            GAPS_LINE,
            "Délku období přepíná tlačítko „Graf … ↻“ nad kartami; pro křivku jsou potřeba alespoň 2 hodnoty.",
        ),
    )

    private fun metricLine(o: ChartInfoOptions): ChartInfo {
        val unit = o.unit.takeIf { it.isNotBlank() }?.let { " v jednotce $it" } ?: ""
        val bullets = buildList {
            add(
                o.metric?.let { "Bod grafu je jedna denní hodnota: ${aggregationPhrase(it)}." }
                    ?: DAILY_VALUE_RULE
            )
            add(
                "Osa Y se přizpůsobí zobrazeným hodnotám (nezačíná od nuly) a má nejmenší rozpětí, aby drobné výkyvy " +
                    "nevypadaly jako velké změny."
            )
            if (o.hasBand) add(USUAL_BAND)
            if (o.hasSecondary) {
                add(
                    "Druhá křivka" + (o.secondaryLabel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                        " je vykreslena ve stejné ose a jednotce jinou barvou; její hodnotu uvidíte při odečtu bodu v hlavičce."
                )
            }
            if (o.isScatter) add("Body jsou vykresleny bez spojnic; kde je pod grafem zónová legenda, barva bodu odpovídá jí.")
            add(
                "Hlavička při odečtu ukazuje hodnotu bodu a změnu oproti první hodnotě období; bez odečtu rozpětí min–max, " +
                    "celkovou změnu (poslední − první) a počet měření."
            )
            add("Klepnutím nebo tažením po grafu vyberete bod; datum je vidět v hlavičce a pod grafem.")
            if (!o.isScatter) add(GAPS_LINE)
        }
        return ChartInfo(
            ChartKey.METRIC_HISTORY_LINE, "Graf vývoje metriky",
            "Křivka ukazuje vývoj jedné metriky v čase$unit. Čas běží zleva doprava, popisky osy X jsou data.",
            null, emptyList(), bullets,
        )
    }

    private fun multiSeries(
        key: ChartKey,
        title: String,
        intro: String,
        series: List<ChartInfoSeries>,
        extra: List<String>,
    ): ChartInfo = ChartInfo(
        key, title, intro,
        "Zobrazené řady (${series.size})",
        series,
        buildList {
            add("Klepnutím na štítek řady v legendě křivku skryjete nebo znovu zobrazíte; barva a styl čáry se přitom nemění.")
            add(
                "Štítek ukazuje hodnotu v odečteném bodě (bez odečtu poslední dostupnou) a v závorce změnu oproti " +
                    "bezprostředně předchozímu bodu osy, pokud ten má hodnotu."
            )
            add(
                "Klepnutím nebo tažením po grafu zobrazíte svislý kurzor a hodnoty všech viditelných řad; " +
                    "klepnutí na stejný bod odečet zruší."
            )
            add(
                "Přepínač „Detail křivek“: každá řada má vlastní svislé měřítko podle svého rozsahu (dobře se porovnává tvar, " +
                    "ne výška). „Společná osa Y“ vykreslí všechny řady na jednu osu od minima po maximum viditelných řad."
            )
            add(
                "Popisky osy Y platí při samostatných měřítkách jen pro první viditelnou řadu (u řady v kg pro ni); " +
                    "hodnoty ostatních čtěte ze štítků."
            )
            addAll(extra)
            add(GAPS_LINE + " Řada s jediným bodem se zobrazí jen jako značka; pro křivky jsou potřeba alespoň 2 body.")
        },
    )

    private fun weightComposition(series: List<ChartInfoSeries>, o: ChartInfoOptions): ChartInfo {
        val unit = o.unit.ifBlank { "kg" }
        val defaultSeries = listOf(
            ChartInfoSeries("weight", "Váha – čára", unit),
            ChartInfoSeries("muscle", "Svaly – modrá plocha dole", unit),
            ChartInfoSeries("fat", "Tuk – oranžová plocha nad svaly", unit),
            ChartInfoSeries("rest", "Zbytek – světlá plocha pod čárou váhy", detail = "kosti a ostatní tkáně, dopočet"),
        )
        return ChartInfo(
            ChartKey.WEIGHT_COMPOSITION_STACKED_AREA, "Složení hmotnosti v čase",
            "Křivka ukazuje vývoj hmotnosti a plocha pod ní, z čeho se hmotnost skládá. Svaly a tuk pocházejí z bioimpedančního " +
                "měření Withings, jeden bod je jedno vážení.",
            "Vrstvy grafu",
            series.ifEmpty { defaultSeries },
            buildList {
                add(
                    "Měřítko „Detail váhy“ (výchozí): osa Y je přiblížená na rozsah váhy a plocha pod čárou je rozdělena " +
                        "v poměru svaly : tuk : zbytek daného vážení. Podíly vrstev jsou pro čitelnost omezeny, výška vrstev je tedy orientační."
                )
                add(
                    "Měřítko „0–max“: osa začíná od nuly a vrstvy jsou skutečné hodnoty ($unit) naskládané na sebe " +
                        "(svaly, nad nimi tuk, pod čárou váhy zbytek)."
                )
                if (o.hasBand) add("V detailním měřítku je za čárou váhy světlé pásmo obvyklého rozmezí P25–P75 vaší váhy.")
                add(
                    "Chybí-li u vážení hodnota svalů nebo tuku, použije se poslední známá hodnota; není-li žádná, graf dosadí " +
                        "hrubý odhad podílu hmotnosti. Takto doplněné vrstvy nejsou naměřená data."
                )
                add("Legenda nad grafem ukazuje hodnoty vybraného (jinak posledního) vážení a změnu oproti předchozímu vážení.")
                add("Klepnutím nebo tažením po grafu vyberete vážení; klepnutí na stejný bod odečet zruší. Popisky osy X jsou data.")
                add(GAPS_LINE)
            },
        )
    }

    private fun bodyBar(series: List<ChartInfoSeries>): ChartInfo = ChartInfo(
        ChartKey.BODY_COMPOSITION_BAR, "Složení těla (pruh)",
        "Vodorovný pruh rozděluje poslední měření složení těla na svaly, tuk a kosti. Data pocházejí z bioimpedance Withings.",
        "Segmenty pruhu",
        series.ifEmpty {
            listOf(
                ChartInfoSeries("muscle", "Svaly – modrý segment"),
                ChartInfoSeries("fat", "Tuk – oranžový segment"),
                ChartInfoSeries("bone", "Kosti – fialový segment"),
            )
        },
        listOf(
            "Šířka segmentu je podíl z jejich součtu (svaly + tuk + kosti), ne z celkové hmotnosti – voda a ostatní tkáně " +
                "v pruhu nejsou. Velmi malé segmenty jsou pro čitelnost mírně rozšířeny.",
            "Čísla pod pruhem jsou skutečné hodnoty v kilogramech, nebo v procentech hmotnosti podle přepínače jednotek.",
            "Segment bez měření se nezobrazí. Bez platné hmotnosti nelze tuk přepočítat z % na kilogramy.",
        ),
    )

    private fun segmentalTable(): ChartInfo = ChartInfo(
        ChartKey.SEGMENTAL_TABLE, "Segmentální složení těla",
        "Tabulka a graf ukazují svalovou nebo tukovou hmotu po částech těla (ruce, trup, nohy) z posledního měření " +
            "Withings Body Scan; datum je v hlavičce.",
        null, emptyList(),
        listOf(
            "Přepínač Svaly / Tuk volí tkáň, přepínač jednotek hmotnost (kg) nebo podíl tkáně v dané části těla (%).",
            "Sloupec Změna je rozdíl oproti předchozímu měření (zelená = u svalů nárůst, u tuku pokles; červená opačný směr; " +
                "šedá bez změny). Podíl z celku je podíl části na součtu všech částí.",
            "Barva hodnoty a proužku v řádku je orientační stupnice aplikace (zelená / jantarová / červená), nejde o lékařské hodnocení.",
            "Symetrie rukou a nohou je rozdíl pravé a levé strany v procentech jejich průměru; aplikace za vyváženou považuje rozdíl do 5 %.",
            "Klepnutím na řádek nebo čip části těla přepnete graf pod tabulkou na vývoj jen této části; " +
                "„Všechny části v grafu“ je zobrazí najednou.",
        ),
    )

    private fun sleepStages(series: List<ChartInfoSeries>): ChartInfo = ChartInfo(
        ChartKey.SLEEP_STAGES_BAR, "Fáze spánku",
        "Pruh ukazuje poměr fází poslední zaznamenané noci v zobrazeném období. Data pocházejí z Garminu.",
        "Fáze v pruhu (zleva)",
        series.ifEmpty {
            listOf(
                ChartInfoSeries("deep", "Hluboký spánek – indigová"),
                ChartInfoSeries("rem", "REM spánek – fialová"),
                ChartInfoSeries("light", "Lehký spánek – modrá"),
                ChartInfoSeries("awake", "Bdění – oranžová"),
            )
        },
        listOf(
            "Šířka úseku je podíl z součtu těchto čtyř fází, ne z celé doby v posteli. Fáze bez údaje se nezobrazí.",
            "Dlaždice pod pruhem ukazují doby jednotlivých fází, počet probuzení a zdřímnutí během dne; zdřímnutí se do pruhu nepočítá.",
        ),
    )

    private fun telemetry(series: List<ChartInfoSeries>): ChartInfo = ChartInfo(
        ChartKey.ACTIVITY_TELEMETRY_CURVES, "Telemetrické křivky aktivity",
        "Karta zobrazuje záznam aktivity po kanálech, jedna křivka na kanál. Kanál se objeví jen tehdy, " +
            "když ho záznam z hodinek obsahuje.",
        "Dostupné kanály (${series.size})",
        series,
        buildList {
            add("Přepínač Osa: Čas / Vzdál. mění vodorovnou osu na uplynulý čas nebo ujetou vzdálenost; označený úsek se při přepnutí zruší.")
            add(
                "Křivka je průměr kanálu v až 160 stejně širokých úsecích osy X, ostré špičky se proto mohou vyhladit. " +
                    "Úsek bez dat (pauza, výpadek) se přeskočí a čára spojí sousední úseky; chybějící hodnota není nula."
            )
            add(
                "„Všechny křivky pod sebou“: každý kanál má vlastní graf, vlastní osu a souhrn nad grafem. Klepnutím nebo tažením " +
                    "po kterékoli křivce nastavíte společný kurzor a panel „Synchronizovaný odečet bodu“ ukáže všechny kanály ve stejném místě záznamu."
            )
            add(
                "„Lupa & Výřez úseku“: jeden vybraný kanál ve větším grafu. Po zapnutí „Označit úsek tažením“ vyznačíte úsek " +
                    "a pod grafem se ukáže jeho čas, vzdálenost, tempo a převýšení; „Jen pohybový čas“ počítá čas bez zastávek."
            )
            if (series.any { it.id == "STAMINA" }) {
                add("U kanálu Výdrž (Stamina) je vedle aktuální výdrže druhá křivka Potenciální výdrž ve stejné ose.")
            }
            add("Kanály běžecké dynamiky se kreslí jako body bez spojnic; kde je pod grafem zónová legenda, barva bodu odpovídá jí.")
            add("Vysvětlení jednotlivých veličin najdete v ⓘ u každého kanálu.")
        },
    )
}
