package app.pulse4.ui

/**
 * Short, plain-language explanations for the (i) button of charts: what the chart shows in everyday
 * terms without jargon or repeating individual legend lines. Never adds medical thresholds.
 */
enum class ChartKey {
    METRIC_HISTORY_LINE,
    METRICS_CORRELATION,
    METRICS_CORRELATION_NORMALIZED,
    WEIGHT_COMPOSITION_STACKED_AREA,
    BODY_COMPOSITION_BAR,
    SEGMENTAL_TABLE,
    SLEEP_STAGES_BAR,
    ECG_STRIP,
    ACTIVITY_TELEMETRY_CURVES,
    ACTIVITY_COMPARE_CHANNEL,
    ACTIVITY_COMPARE_ROUTES,
    GROUP_COMPARE,
}

data class ChartInfo(
    val key: ChartKey,
    val title: String,
    val intro: String,
    val bullets: List<String> = emptyList(),
) {
    /** Whole dialog as text (tests, accessibility, logs). */
    fun plainText(): String = buildList {
        add(title)
        add(intro)
        bullets.forEach { add("• $it") }
    }.joinToString("\n")
}

/** Per-chart context that adds an optional short note when extra visual elements are drawn. */
data class ChartInfoOptions(
    val hasBand: Boolean = false,
    val hasSecondary: Boolean = false,
    val secondaryLabel: String = "",
    val isScatter: Boolean = false,
)

object ChartInfoTexts {

    const val DIALOG_CLOSE = "Rozumím"
    const val BUTTON_DESCRIPTION = "Vysvětlení grafu"

    fun build(
        key: ChartKey,
        options: ChartInfoOptions = ChartInfoOptions(),
    ): ChartInfo = when (key) {
        ChartKey.METRIC_HISTORY_LINE -> metricLine(options)
        ChartKey.METRICS_CORRELATION -> ChartInfo(
            key = key,
            title = "Porovnání vývoje",
            intro = "Ukazuje společně vývoj vybraných hodnot v čase, abyste na první pohled viděli, jak se mění spolu (např. když roste váha, jestli přibývají svaly nebo tuk).",
            bullets = listOf(
                "V režimu Jen tvar změn ukazuje graf jen to, kdy která hodnota roste nebo klesá – ne jejich skutečnou výšku vůči sobě."
            ),
        )
        ChartKey.METRICS_CORRELATION_NORMALIZED -> ChartInfo(
            key = key,
            title = "Porovnání změn (0–100 %)",
            intro = "Ukazuje jen směr změn jednotlivých hodnot v čase. Každá křivka je roztažená od svého nejnižšího (0 %) po nejvyšší (100 %) bod, takže můžete porovnat i různé jednotky.",
        )
        ChartKey.WEIGHT_COMPOSITION_STACKED_AREA -> ChartInfo(
            key = key,
            title = "Z čeho se skládá váha",
            intro = "Horní čára ukazuje vaši celkovou váhu v čase a barevné plochy pod ní ukazují podíl svalů a tuku. Snadno tak poznáte, jestli přibíráte nebo hubnete ze svalů, nebo z tuku.",
            bullets = listOf(
                "Režim Detail změn přiblíží malé výkyvy váhy (výška barev je orientační); režim Od nuly ukazuje skutečný poměr z celé váhy."
            ),
        )
        ChartKey.BODY_COMPOSITION_BAR -> ChartInfo(
            key = key,
            title = "Složení těla",
            intro = "Ukazuje, jak velkou část vaší poslední naměřené váhy tvoří svaly, tuk a kosti.",
        )
        ChartKey.SEGMENTAL_TABLE -> ChartInfo(
            key = key,
            title = "Složení podle částí těla",
            intro = "Ukazuje, kolik svalů nebo tuku máte v rukou, trupu a nohou, jestli jsou levá a pravá strana vyvážené i jak se hodnoty vyvíjí v čase.",
        )
        ChartKey.SLEEP_STAGES_BAR -> ChartInfo(
            key = key,
            title = "Fáze spánku",
            intro = "Ukazuje rozdělení poslední noci na hluboký spánek, snění (REM), lehký spánek a probuzení.",
        )
        ChartKey.ECG_STRIP -> ChartInfo(
            key = key,
            title = "Orientační EKG křivka",
            intro = "Jde jen o ukázkový obrázek EKG vlny, ne o váš skutečný záznam. Naměřené výsledky z váhy jsou uvedeny nad obrázkem.",
        )
        ChartKey.ACTIVITY_TELEMETRY_CURVES -> ChartInfo(
            key = key,
            title = "Průběh aktivity",
            intro = "Ukazuje, jak se během aktivity měnily naměřené hodnoty (např. tep nebo tempo) podle času nebo vzdálenosti.",
        )
        ChartKey.ACTIVITY_COMPARE_CHANNEL -> ChartInfo(
            key = key,
            title = "Porovnání aktivit",
            intro = "Porovnává průběh vybrané hodnoty (např. tepu nebo tempa) u více aktivit od startu do cíle.",
        )
        ChartKey.ACTIVITY_COMPARE_ROUTES -> ChartInfo(
            key = key,
            title = "Porovnání tras na mapě",
            intro = "Zobrazuje GPS trasy vybraných aktivit přes sebe na jedné mapě.",
        )
        ChartKey.GROUP_COMPARE -> ChartInfo(
            key = key,
            title = "Porovnání v kruhu",
            intro = "Porovnává vývoj vybrané hodnoty mezi vámi a ostatními členy kruhu, kteří ji s vámi sdílí.",
        )
    }

    private fun metricLine(o: ChartInfoOptions): ChartInfo {
        val bullets = buildList {
            if (o.hasBand) {
                add("Světlý pruh na pozadí ukazuje vaše obvyklé rozmezí za toto období.")
            }
            if (o.hasSecondary) {
                val label = o.secondaryLabel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                add("Druhá čára slouží pro porovnání$label.")
            }
            if (o.isScatter) {
                add("Jednotlivé body ukazují naměřené hodnoty.")
            }
        }
        return ChartInfo(
            key = ChartKey.METRIC_HISTORY_LINE,
            title = "Vývoj v čase",
            intro = "Ukazuje, jak se hodnota měnila během vybraného období.",
            bullets = bullets,
        )
    }
}
