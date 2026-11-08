@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.analytics.BestEffort
import app.pulse4.analytics.RunningDynamicsModel
import app.pulse4.android.ui.theme.MineralOutlineDark
import app.pulse4.android.ui.theme.MineralSurfaceDark
import app.pulse4.android.ui.theme.MineralSurfaceVariantDark
import app.pulse4.ui.Channel

enum class TelemetryTopic {
    TRAINING_EFFECT,
    STAMINA,
    PERFORMANCE_CONDITION,
    CADENCE,
    STRIDE_LENGTH,
    GROUND_CONTACT_TIME,
    GROUND_CONTACT_BALANCE,
    VERTICAL_OSCILLATION,
    VERTICAL_RATIO,
    RELATIVE_EFFORT,
    GAP,
    BEST_EFFORTS,
    HEART_RATE,
    SPEED,
    ELEVATION,
    POWER,
    HR_ZONES,
    POWER_ZONES,
    RUNNING_DYNAMICS,
    LAPS
}

data class TelemetryInfoContent(
    val title: String,
    val subtitle: String,
    val description: String,
    val benchmark: String? = null,
    val howToUse: String? = null,
)

fun getTelemetryTopicContent(topic: TelemetryTopic): TelemetryInfoContent = when (topic) {
    TelemetryTopic.TRAINING_EFFECT -> TelemetryInfoContent(
        title = "Tréninkový přínos (Training Effect)",
        subtitle = "2D matice aerobní a anaerobní adaptace",
        description = "Tréninkový efekt měří dopad tělesné zátěže na vaši kardiovaskulární kondici na stupnici 0.0 až 5.0 ve dvou nezávislých rovinách:\n\n• Aerobní efekt: Rozvoj vytrvalosti, kapacity plic a schopnosti těla spalovat tuky a šetřit glykogen.\n• Anaerobní efekt: Rozvoj schopnosti pracovat při vysokém zakyselení (laktátu), výbušné síly a maximální rychlosti.\n\nNeonový pulzující bod v 2D matici ukazuje přesné souřadnice vaší výsledné adaptace.",
        benchmark = "• 1.0–1.9: Regenerace (aktivní odpočinek, obnova svalů)\n• 2.0–2.9: Udržení a stavba základní vytrvalosti (Zone 2)\n• 3.0–3.9: Zlepšení tempové vytrvalosti a VO2 Max\n• 4.0–4.9: Výrazný skokový stimul (závodní simulace, intervaly)\n• 5.0: Přepětí (vyžaduje delší odpočinek před dalším tréninkem)",
        howToUse = "Většina tréninkového objemu (cca 80 %) by měla cílit na Aerobní základ (2.0–3.0) s nízkým anaerobním dopadem. Zbytek doplňte intenzivními intervaly."
    )
    TelemetryTopic.STAMINA -> TelemetryInfoContent(
        title = "Garmin Stamina (Výdrž)",
        subtitle = "Okamžitá vs. Potenciální energetická rezerva organismu",
        description = "Stamina modeluje v reálném čase vyčerpání vašich energetických zásob:\n\n1. Okamžitá výdrž (Skutečná stamina, 0–100 %):\nUdává, kolik energie máte právě teď pro zrychlení nebo sprint. Při běhu v pásmu Z4/Z5 prudce klesá. Pokud zpomalíte do chůze či klusu, okamžitá výdrž se regeneruje směrem k potenciální výdrži.\n\n2. Potenciální výdrž (Celková stamina, 0–100 %):\nUdává celkový zbývající energetický strop vašeho těla (svalový glykogen a centrální únava). Pozvolna klesá celkovou délkou aktivity a během běhu ji nelze obnovit.",
        benchmark = "• 100 %: Plně odpočatý start\n• 50 %: Polovina celkové energetické kapacity\n• 0 % okamžité staminy: Tělo nemůže zrychlit a přechází na nouzový režim\n• 0 % potenciální staminy: Totální vyčerpání organismu",
        howToUse = "V maratonu či půlmaratonu držte tempo tak, aby okamžitá výdrž neklesala výrazně pod potenciální výdrž dříve než v závěrečných kilometrech."
    )
    TelemetryTopic.PERFORMANCE_CONDITION -> TelemetryInfoContent(
        title = "Zhodnocení kondice (Performance Condition)",
        subtitle = "Aktuální připravenost organismu v reálném čase (-5 až +5)",
        description = "Vyhodnocuje se automaticky po prvních 6 až 10 minutách běhu po zahřátí. Algoritmus porovnává vaše aktuální tempo a kadenci vůči srdečnímu tepu a srovnává je s vaší dlouhodobou křivkou VO2 Max.",
        benchmark = "• +4 až +5: Špičkový den (tep je podstatně nižší než obvykle při daném tempu)\n• +1 až +3: Dobrá kondice, vysoká běžecká ekonomika\n• 0: Norma, obvyklý výkon\n• -1 až -3: Lehká únava, dehydratace nebo horko\n• -4 až -5: Výrazná únava nebo přetrénování, doporučuje se zvolnit",
        howToUse = "Pokud hodnota po zahřátí ukazuje +3 a více, je vhodný den na osobní rekord nebo ostrý trénink. Při záporných hodnotách trénink nepřepínejte."
    )
    TelemetryTopic.CADENCE -> TelemetryInfoContent(
        title = "Kadence (Frekvence kroků)",
        subtitle = "Počet kroků za minutu (spm)",
        description = "Kadence udává celkový počet kroků obou nohou za minutu. Rychlejší frekvence kroků s kratším dopadem šetří klouby a snižuje brzdné síly při došlapu.",
        benchmark = "• < 153 spm: Nízká kadence (červená, riziko overstridingu)\n• 153–163 spm: Mírná kadence (oranžová)\n• 164–173 spm: Dobrá kadence (zelená, standard hobby běžců)\n• 174–183 spm: Výborná kadence (modrá, ideál pro efektivní běh)\n• > 183 spm: Špičková kadence (fialová, tempo a sprint)",
        howToUse = "Cílem pro většinu běžců je držet frekvenci mezi 165 až 180 spm i při pomalejším regeneračním tempu."
    )
    TelemetryTopic.STRIDE_LENGTH -> TelemetryInfoContent(
        title = "Délka kroku",
        subtitle = "Vzdálenost mezi dvěma po sobě jdoucími došlapy (m / cm)",
        description = "Délka kroku je přímo úměrná rychlosti běhu a kadenci. Vyšší délky kroku by mělo být dosaženo silným odrazem vzad, nikoliv vykopáváním nohy dopředu před těžiště.",
        benchmark = "• Klus / jogging: 0.85 – 1.15 m\n• Vytrvalostní tempo: 1.15 – 1.45 m\n• Rychlé intervaly a závody: 1.45 – 1.85 m",
        howToUse = "Nezvyšujte rychlost natahováním kroku dopředu (overstriding vede k bolestem holení a kolen), ale zvýšením kadence a silnějším odrazem."
    )
    TelemetryTopic.GROUND_CONTACT_TIME -> TelemetryInfoContent(
        title = "Doba kontaktu se zemí (GCT)",
        subtitle = "Čas v milisekundách (ms), po který noha spočívá na zemi",
        description = "Kratší doba kontaktu se zemí znamená vyšší elasticitu šlach, lepší návratnost energie a vyšší běžeckou rychlost.",
        benchmark = "• > 305 ms: Dlouhý kontakt (červená, typické pro chůzi či pomalý běh)\n• 273–305 ms: Průměrný kontakt (oranžová)\n• 241–272 ms: Dobrý kontakt (zelená, běžní vytrvalci)\n• 208–240 ms: Výborný kontakt (modrá, zkušení běžci)\n• < 208 ms: Elitní kontakt (fialová, špičkoví atleti a sprinteři)",
        howToUse = "Plyometrický trénink, švihadlo a běžecká abeceda pomáhají zkrátit dobu kontaktu a zlepšit pružnost achilovek."
    )
    TelemetryTopic.GROUND_CONTACT_BALANCE -> TelemetryInfoContent(
        title = "Vyváženost kontaktu se zemí L/P",
        subtitle = "Symetrie doby došlapu levé a pravé nohy v %",
        description = "Měří rovnoměrnost zatížení obou dolních končetin. Výrazná asymetrie často signalizuje svalovou nerovnováhu, špatný sklon vozovky nebo počínající zranění.",
        benchmark = "• 49.3 – 50.7 %: Symetrický došlap (zelená, ideální stav)\n• 48.0 – 49.2 % / 50.8 – 52.0 %: Mírná asymetrie (oranžová)\n• < 48.0 % / > 52.0 %: Výrazná asymetrie (červená, riziko přetížení jedné strany)",
        howToUse = "Pokud se asymetrie soustavně odchyluje na jednu stranu, zaměřte se na posílení slabší nohy a střed těla (core)."
    )
    TelemetryTopic.VERTICAL_OSCILLATION -> TelemetryInfoContent(
        title = "Vertikální oscilace",
        subtitle = "Výška vertikálního posunu těžiště při každém kroku (cm)",
        description = "Měří, o kolik centimetrů se vaše těžiště zvedne při odrazu. Příliš vysoká oscilace znamená plýtvání energií do výšky namísto pohybu vpřed.",
        benchmark = "• < 6.1 cm: Špičková ekonomika (fialová, minimum promrhané energie)\n• 6.1–7.4 cm: Výborná hodnota (modrá)\n• 7.5–8.6 cm: Dobrý průměr (zelená)\n• 8.7–10.1 cm: Vyšší oscilace (oranžová)\n• > 10.1 cm: Nadměrné skákání (červená, neefektivní běh)",
        howToUse = "Zvýšení kadence a náklon celého těla od kotníků dopředu automaticky snižují vertikální oscilaci."
    )
    TelemetryTopic.VERTICAL_RATIO -> TelemetryInfoContent(
        title = "Vertikální poměr",
        subtitle = "Poměr vertikální oscilace k délce kroku v %",
        description = "Ukazuje procento energie investované do výšky vůči získané vzdálenosti. Čím nižší poměr, tím hospodárnější a ekonomičtější je váš běžecký krok.",
        benchmark = "• < 6.1 %: Elitní běžecká ekonomika (fialová)\n• 6.1–7.4 %: Výborná hodnota (modrá)\n• 7.5–8.6 %: Dobrý průměr (zelená)\n• 8.7–10.0 %: Mírně neefektivní (oranžová)\n• > 10.0 %: Neefektivní pohyb (červená)",
        howToUse = "Vertikální poměr pod 7 % je známkou vysoce efektivního přenosu síly dopředu."
    )
    TelemetryTopic.RELATIVE_EFFORT -> TelemetryInfoContent(
        title = "Kardiovaskulární úsilí (Suffer score)",
        subtitle = "Integrovaná zátěž srdce napříč tepovými zónami Z1–Z5",
        description = "Metrika integruje dobu strávenou v jednotlivých zónách srdečního tepu s exponenciálním vahováním (Z5 má podstatně vyšší váhu než Z1). Umožňuje objektivně porovnat náročnost krátkého ostrého tréninku s dlouhým pomalým během.\n\nPočítá se lokálně z vašeho tepového záznamu aktivity.",
        benchmark = "• 0–30: Lehké úsilí (regenerace, aktivní odpočinek)\n• 31–75: Střední úsilí (vytrvalostní základ)\n• 76–150: Těžký trénink (výrazný rozvoj kapacity)\n• > 150: Extrémní / závodní úsilí (vyžaduje několik dní na zotavení)",
        howToUse = "Sledujte týdenní součet úsilí pro prevenci přetrénování a postupné budování kondice."
    )
    TelemetryTopic.GAP -> TelemetryInfoContent(
        title = "GAP (Tempo upravené o sklon)",
        subtitle = "Rovinatý ekvivalent tempa dle Minettiho biomechanického modelu",
        description = "Běh do kopce vyžaduje více energie na překonání gravitace, zatímco běh z kopce energii šetří (do určitého sklonu). GAP přepočítává každou sekundu na tempo, jakým byste běželi po absolutní rovině při stejném energetickém výdeji.\n\nVyužívá Minettiho vědecký polynom: cost = 155.4·g⁵ - 30.4·g⁴ - 32.8·g³ + 43.4·g² + 13.5·g + 3.6.",
        benchmark = "• Do kopce: GAP je výrazně rychlejší než reálné tempo (např. reál 6:00/km v kopci odpovídá rovině 4:45/km)\n• Z kopce: GAP je pomalejší než reálné tempo",
        howToUse = "V kopcovitém terénu se řiďte GAPem nebo tepem, nikoliv reálným tempem, abyste nepřepálili úvod."
    )
    TelemetryTopic.BEST_EFFORTS -> TelemetryInfoContent(
        title = "Nejlepší úseky (Best Efforts)",
        subtitle = "Rekordní atletická okna detekovaná z GPS trasy",
        description = "Algoritmus prochází kompletní GPS křivku a posuvným oknem hledá nejrychlejší souvislý čas na standardních atletických distancích (400 m, 1 km, 1 míle, 5 km). U každého úseku počítá i čistý profil převýšení.",
        benchmark = "Ohodnocení nejlepších úseků: #1 400 m, #2 1 km, #3 1 míle, #4 5 km.",
        howToUse = "Porovnávejte nejlepší úseky mezi tréninky pro sledování rychlostního progresu i v rámci dlouhých běhů."
    )
    TelemetryTopic.HEART_RATE -> TelemetryInfoContent(
        title = "Srdeční tep",
        subtitle = "Tepová frekvence v úderech za minutu (bpm)",
        description = "Klíčový ukazatel intenzity tělesné zátěže. Členění do zón Z1 až Z5 odráží různé metabolické procesy od spalování tuků po anaerobní glykolýzu.",
        benchmark = "• Z1: <60% HRmax (regenerace)\n• Z2: 60–70% HRmax (základní vytrvalost)\n• Z3: 70–80% HRmax (aerobní tempo)\n• Z4: 80–90% HRmax (laktátový práh)\n• Z5: >90% HRmax (VO2 Max a anaerobie)"
    )
    TelemetryTopic.SPEED -> TelemetryInfoContent(
        title = "Tempo a Rychlost",
        subtitle = "Minuty na kilometr (min/km) a km/h",
        description = "Tempo udává čas potřebný k uběhnutí jednoho kilometru. Plynulé rovnoměrné tempo bez prudkých výkyvů šetří svalový glykogen."
    )
    TelemetryTopic.ELEVATION -> TelemetryInfoContent(
        title = "Nadmořská výška",
        subtitle = "Výškový profil trasy v metrech",
        description = "Zobrazuje stoupání a klesání v průběhu trasy zaznamenané barometrem nebo GPS. Pomáhá vysvětlit výkyvy v tepu a tempu."
    )
    TelemetryTopic.POWER -> TelemetryInfoContent(
        title = "Běžecký výkon (Running Power)",
        subtitle = "Mechanický výkon ve wattech (W)",
        description = "Okamžitý výkon reflektuje jak rychlost, tak sklon terénu a protivítr bez zpoždění srdečního tepu. Ideální nástroj pro rovnoměrné dávkování tempa v kopcích."
    )
    TelemetryTopic.HR_ZONES -> TelemetryInfoContent(
        title = "Čas v tepových zónách (Garmin Z1–Z5)",
        subtitle = "Fyziologická distribuce srdeční zátěže dle laktátového prahu",
        description = "Zónové členění rozděluje trénink do 5 pásem na základě maximální tepové frekvence:\n\n• Zóna 1 (Zahřátí / Regenerace, 50–60 % HRmax): Obnova svalů a prokrvení.\n• Zóna 2 (Snadné / Vytrvalost, 60–70 % HRmax): Základní aerobní kapacita a efektivní oxidace tuků.\n• Zóna 3 (Aerobní / Tempové, 70–80 % HRmax): Zvýšení kapacity kardiovaskulárního systému.\n• Zóna 4 (Prahové, 80–90 % HRmax): Laktátový anaerobní práh, schopnost udržet vysoké tempo bez tuhnutí nohou.\n• Zóna 5 (Maximální, >90 % HRmax): VO2 Max, maximální kyslíková spotřeba a anaerobní glykolýza.",
        benchmark = "Pro zdravou polarizaci tréninku (pravidlo 80/20) by mělo 75–80 % ročního objemu probíhat v Zóně 2 a Zóně 1.",
        howToUse = "Pokud máte v regeneračním běhu příliš mnoho času v Z3 a Z4, zpomalte tempo pro lepší regeneraci."
    )
    TelemetryTopic.POWER_ZONES -> TelemetryInfoContent(
        title = "Čas ve výkonových zónách (Watty Z1–Z5)",
        subtitle = "Přesné dávkování mechanické práce bez kardiovaskulární setrvačnosti",
        description = "Výkonové zóny (Garmin power model) sledují okamžitý mechanický výdej ve wattech. Na rozdíl od tepu, který reaguje se zpožděním 30–60 sekund, výkon okamžitě odhalí nástup do kopce či přepálení tempa.",
        benchmark = "• Z1: Aktivní regenerace (<55 % FTP)\n• Z2: Vytrvalostní výkon (56–75 % FTP)\n• Z3: Tempový výkon (76–90 % FTP)\n• Z4: Prahový výkon (91–105 % FTP)\n• Z5: Anaerobní kapacita (>106 % FTP)"
    )
    TelemetryTopic.RUNNING_DYNAMICS -> TelemetryInfoContent(
        title = "Dynamika běhu (Garmin Running Dynamics)",
        subtitle = "Biomechanická analýza kroku a ekonomiky běhu",
        description = "Komplexní sada biomechanických měření získaná z hrudního pásu (HRM-Pro) nebo hodinek: Kadence, Délka kroku, Doba kontaktu se zemí (GCT), Vyváženost L/P, Vertikální oscilace a Vertikální poměr. Společně odhalují, jak hospodárně vaše tělo převádí energii do pohybu vpřed a kde hrozí riziko asymetrického zranění.",
        benchmark = "Optimální hodnoty: Kadence 170–180 spm, GCT < 240 ms, Vertikální poměr < 7.5 %, Symetrie L/P 49.5–50.5 %."
    )
    TelemetryTopic.LAPS -> TelemetryInfoContent(
        title = "Mezičasy a kola (Laps & Splits)",
        subtitle = "Analýza kilometrových úseků a tempové stability",
        description = "Přehled jednotlivých kilometrů, automatických kol hodinek a splitů (fází běhu/chůze). Umožňuje zkontrolovat pacing strategii — ideálem je 'negativní split', kdy je druhá polovina aktivity mírně rychlejší než první.",
        howToUse = "Sledujte odchylky tempa a tepu mezi jednotlivými koly pro odhalení nástupu únavy v závěru trasy."
    )
}

fun Channel.toTelemetryTopic(): TelemetryTopic? = when (this) {
    Channel.HEART_RATE -> TelemetryTopic.HEART_RATE
    Channel.SPEED -> TelemetryTopic.SPEED
    Channel.ELEVATION -> TelemetryTopic.ELEVATION
    Channel.CADENCE -> TelemetryTopic.CADENCE
    Channel.POWER -> TelemetryTopic.POWER
    Channel.STRIDE_LENGTH -> TelemetryTopic.STRIDE_LENGTH
    Channel.GROUND_CONTACT_TIME -> TelemetryTopic.GROUND_CONTACT_TIME
    Channel.GROUND_CONTACT_BALANCE -> TelemetryTopic.GROUND_CONTACT_BALANCE
    Channel.VERTICAL_OSCILLATION -> TelemetryTopic.VERTICAL_OSCILLATION
    Channel.VERTICAL_RATIO -> TelemetryTopic.VERTICAL_RATIO
    Channel.STAMINA -> TelemetryTopic.STAMINA
    Channel.PERFORMANCE_CONDITION -> TelemetryTopic.PERFORMANCE_CONDITION
}

@Composable
fun InfoBadgeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E293B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Informace",
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun TelemetryInfoDialog(
    content: TelemetryInfoContent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Rozumím", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(content.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(content.subtitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                Text(content.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                content.benchmark?.let { bm ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Assessment,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Referenční hodnoty a zóny:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(bm, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                    }
                }
                content.howToUse?.let { htu ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Tréninkové doporučení:\n$htu", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                    }
                }
            }
        },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun TelemetryInfoDialog(
    topic: TelemetryTopic,
    onDismiss: () -> Unit
) {
    TelemetryInfoDialog(content = getTelemetryTopicContent(topic), onDismiss = onDismiss)
}

/**
 * Garmin styl: Vyváženost kontaktu se zemí L/P
 * Dvoubarevná lišta (Levá noha světle modrá #38BDF8 vs. Pravá noha zelená #34D399),
 * 50% středová ryska a odznak symetrie / asymetrie.
 */
@Composable
fun GarminGroundContactBalanceMeter(
    balanceLeftPct: Double,
    modifier: Modifier = Modifier,
    onInfoClick: (() -> Unit)? = null
) {
    val left = balanceLeftPct.coerceIn(35.0, 65.0)
    val right = 100.0 - left
    val diff = kotlin.math.abs(left - 50.0)
    val (statusLabel, statusColor, statusIcon) = when {
        diff <= 0.7 -> Triple("Symetrický došlap", Color(0xFF10B981), Icons.Outlined.CheckCircle)
        diff <= 1.8 -> Triple("Mírná odchylka (${if (left > 50) "převažuje L" else "převažuje P"})", Color(0xFFF59E0B), Icons.Outlined.Warning)
        else -> Triple("Asymetrie (${if (left > 50) "přetížení L" else "přetížení P"})", Color(0xFFEF4444), Icons.Outlined.Warning)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2030)),
        border = BorderStroke(1.dp, Color(0xFF2D3748)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Scale,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Vyváženost kontaktu se zemí",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (onInfoClick != null) {
                        Spacer(Modifier.width(6.dp))
                        InfoBadgeButton(onClick = onInfoClick)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Levá ${String.format(java.util.Locale.US, "%.1f", left)} %",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF38BDF8)
                )
                Text(
                    "Pravá ${String.format(java.util.Locale.US, "%.1f", right)} %",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF34D399)
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF0F172A))
            ) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight((left / 100.0).toFloat())
                            .background(Color(0xFF38BDF8))
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight((right / 100.0).toFloat())
                            .background(Color(0xFF34D399))
                    )
                }
                // Středová ryska (50% rovnováha)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.9f))
                )
            }
        }
    }
}

/**
 * Komplexní Garmin Training Effect karta ve stylu Garmin Connect a Firstbeat Analytics:
 * - 2D Matice tréninkového efektu (4 sloupce dole = Aerobní, 3 řádky vpravo = Anaerobní)
 * - Svítící pulzující bod (Dot) lokalizující přesný dopad tréninku na kardiovaskulární systém
 * - Dva samostatné barevně segmentované ukazatele (Bary) pro Aerobní i Anaerobní efekt s ukazatelem (Dot)
 * - Detailní zpráva Firstbeat a souhrn zátěže (Training Load, Body Battery, Intenzivní minuty)
 */
@Composable
fun GarminTrainingEffectCard(
    aerobic: Double,
    anaerobic: Double,
    label: String?,
    aerobicMsg: String?,
    anaerobicMsg: String?,
    trainingLoad: Double? = null,
    bodyBatteryImpact: Int? = null,
    moderateMinutes: Int? = null,
    vigorousMinutes: Int? = null,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.TRAINING_EFFECT),
            onDismiss = { showInfo = false }
        )
    }

    val aeClamped = aerobic.coerceIn(0.0, 5.0)
    val anClamped = anaerobic.coerceIn(0.0, 5.0)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Záhlaví karty
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GARMIN TRÉNINKOVÝ EFEKT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                val effectTitle = formatTrainingEffectLabel(label)
                Text(
                    effectTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981),
                    modifier = Modifier
                        .background(Color(0xFF10B981).copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // SEKCE 1: 2D MATICE TRÉNINKOVÉHO EFEKTU (4 bary dole × 3 bary vpravo)
            Text(
                "MATICE VLIVU NA VÝKONNOST (AEROBNÍ × ANAEROBNÍ)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(165.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                    .padding(8.dp)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height

                    // 4 sloupce (Aerobní: 1-2 Regenerace, 2-3 Základ, 3-4 Tempo, 4-5 Práh)
                    val colW = w / 4f
                    // 3 řádky (Anaerobní: 0-1 Žádný, 1-3 Udržování, 3-5 Kapacita/Rychlost)
                    val rowH = h / 3f

                    // Mřížka
                    for (c in 0..4) {
                        val gx = c * colW
                        drawLine(Color(0xFF1E293B), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.2f)
                    }
                    for (r in 0..3) {
                        val gy = r * rowH
                        drawLine(Color(0xFF1E293B), Offset(0f, gy), Offset(w, gy), strokeWidth = 1.2f)
                    }

                    // Zvýraznění aktivní buňky podkladu
                    val activeCol = ((aeClamped - 1.0) / 1.0).toInt().coerceIn(0, 3)
                    val activeRow = when {
                        anClamped >= 3.0 -> 0 // Horní řádek = Kapacita
                        anClamped >= 1.0 -> 1 // Prostřední = Udržování
                        else -> 2             // Spodní = Bez anaerobního
                    }
                    val cellRect = Rect(
                        activeCol * colW + 2f,
                        activeRow * rowH + 2f,
                        (activeCol + 1) * colW - 2f,
                        (activeRow + 1) * rowH - 2f
                    )
                    drawRoundRect(
                        Color(0xFF38BDF8).copy(alpha = 0.12f),
                        Offset(cellRect.left, cellRect.top),
                        Size(cellRect.width, cellRect.height),
                        CornerRadius(6f, 6f)
                    )

                    // Souřadnice puntíku (Dot):
                    // Osa X: Aerobní (1.0 = levý okraj, 5.0 = pravý okraj)
                    val dotX = (((aeClamped - 1.0) / 4.0).coerceIn(0.04, 0.96) * w).toFloat()
                    // Osa Y: Anaerobní (5.0 = horní okraj, 0.0 = spodní okraj)
                    val dotY = ((1.0 - (anClamped / 5.0).coerceIn(0.04, 0.96)) * h).toFloat()

                    // Neonová koróna
                    drawCircle(
                        Color(0xFF38BDF8).copy(alpha = 0.28f),
                        radius = 16f,
                        center = Offset(dotX, dotY)
                    )
                    // Vnější barevný prstenec
                    drawCircle(
                        Color(0xFF0284C7),
                        radius = 9f,
                        center = Offset(dotX, dotY)
                    )
                    // Vnitřní zářící bod
                    drawCircle(
                        Color(0xFF38BDF8),
                        radius = 6f,
                        center = Offset(dotX, dotY)
                    )
                    // Bílý střed puntíku
                    drawCircle(
                        Color.White,
                        radius = 3.2f,
                        center = Offset(dotX, dotY)
                    )
                }

                // Popisky matice
                Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Anaerobní kapacita (3.0+)", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFFA855F7))
                        Text("Sprint / VO2 Max", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFFF43F5E))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Udržování anaerobní báze (1.0–2.9)", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFF64748B))
                        Text("Laktátový práh", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFFF59E0B))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bez anaerobního efektu (<1.0)", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFF475569))
                        Text("Dlouhý běh", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color(0xFF10B981))
                    }
                }
            }

            // Osa X dole – 4 segmenty
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1.0 Regenerace", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFF38BDF8))
                Text("2.0 Základ", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFF10B981))
                Text("3.0 Tempo", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFFF59E0B))
                Text("4.0–5.0 Práh", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFFEF4444))
            }

            // SEKCE 2: DVA SAMOSTATNÉ HORIZONTÁLNÍ BARY (AEROBNÍ & ANAEROBNÍ) S PUNTÍKEM
            GarminSegmentedGaugeBar(
                title = "Aerobní Training Effect",
                score = aeClamped,
                message = aerobicMsg ?: formatAerobicMessage(aeClamped),
                primaryColor = Color(0xFF10B981)
            )

            Spacer(Modifier.height(12.dp))

            GarminSegmentedGaugeBar(
                title = "Anaerobní Training Effect",
                score = anClamped,
                message = anaerobicMsg ?: formatAnaerobicMessage(anClamped),
                primaryColor = Color(0xFFA855F7)
            )

            // SEKCE 3: TRÉNINKOVÁ ZÁTĚŽ, BODY BATTERY A MINUTY
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        trainingLoad?.let { "%.0f".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8)
                    )
                    Text("Tréninková zátěž", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        bodyBatteryImpact?.let { "${if (it > 0) "+$it" else "$it"} BB" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF59E0B)
                    )
                    Text("Dopad Body Battery", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
                Column(Modifier.weight(1f)) {
                    val mod = moderateMinutes ?: 0
                    val vig = vigorousMinutes ?: 0
                    val total = mod + vig * 2
                    Text(
                        if (total > 0) "$total min" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981)
                    )
                    Text("Intenzivní minuty", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

/**
 * Segmentovaný horizontální progress bar Garmin s ukazatelem (Dot) a slovním hodnocením.
 */
@Composable
private fun GarminSegmentedGaugeBar(
    title: String,
    score: Double,
    message: String,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val tierLabel = when {
        score < 1.0 -> "Bez přínosu"
        score < 2.0 -> "Regenerace"
        score < 3.0 -> "Udržování"
        score < 4.0 -> "Zlepšování"
        score < 5.0 -> "Výrazné zlepšování"
        else -> "Přepětí"
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tierLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    "%.1f / 5.0".format(score),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 5 segmentů s ukazatelem (Dot)
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            Canvas(Modifier.matchParentSize()) {
                val segCount = 5
                val gap = 3.dp.toPx()
                val totalGap = gap * (segCount - 1)
                val segW = (size.width - totalGap) / segCount
                val segH = 7.dp.toPx()
                val barTop = (size.height - segH) / 2f

                val colors = listOf(
                    Color(0xFF475569), // 0-1 Žádný
                    Color(0xFF38BDF8), // 1-2 Regenerace
                    Color(0xFF10B981), // 2-3 Udržování
                    Color(0xFFF59E0B), // 3-4 Zlepšování
                    Color(0xFFEF4444)  // 4-5 Přepětí
                )

                for (i in 0 until segCount) {
                    val left = i * (segW + gap)
                    val active = score >= (i + 1) * 0.95
                    val col = if (score >= i) colors[i] else colors[i].copy(alpha = 0.25f)
                    drawRoundRect(
                        col,
                        Offset(left, barTop),
                        Size(segW, segH),
                        CornerRadius(3f, 3f)
                    )
                }

                // Ukazatel (Dot) na pozici skóre
                val dotFrac = (score / 5.0).coerceIn(0.02, 0.98)
                val dotX = (dotFrac * size.width).toFloat()
                val dotY = size.height / 2f

                drawCircle(Color.White, radius = 5.5f, center = Offset(dotX, dotY))
                drawCircle(primaryColor, radius = 3.5f, center = Offset(dotX, dotY))
            }
        }

        Text(
            message.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Garmin Stamina Hero karta (Skutečná výdrž vs Potenciální výdrž).
 */
@Composable
fun GarminStaminaCard(
    currentStaminaPct: Double,
    potentialStaminaPct: Double,
    distanceM: Double? = null,
    durationS: Double? = null,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.STAMINA),
            onDismiss = { showInfo = false }
        )
    }

    val curClamped = currentStaminaPct.coerceIn(0.0, 100.0)
    val potClamped = potentialStaminaPct.coerceIn(curClamped, 100.0)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0EA5E9))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GARMIN STAMINA (VÝDRŽ)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0EA5E9)
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                Text(
                    "Vyčerpáno ${(100.0 - curClamped).toInt()} %",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF0EA5E9)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${curClamped.toInt()} %",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0EA5E9)
                        )
                    }
                    Text("Okamžitá výdrž (Aktuální)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Energie pro tempo a sprinty (regeneruje se)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${potClamped.toInt()} %",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF59E0B)
                        )
                    }
                    Text("Potenciální výdrž (Celková)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Dlouhodobý strop těla (pouze klesá)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Dvojitý ukazatel kapacity
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF1E293B))
            ) {
                // Potenciální výdrž (zlatá/jantarová)
                Box(
                    Modifier
                        .fillMaxWidth((potClamped / 100.0).toFloat())
                        .height(14.dp)
                        .background(Color(0xFFF59E0B).copy(alpha = 0.5f))
                )
                // Skutečná výdrž (azurová)
                Box(
                    Modifier
                        .fillMaxWidth((curClamped / 100.0).toFloat())
                        .height(14.dp)
                        .background(Color(0xFF0EA5E9))
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF0EA5E9)))
                    Spacer(Modifier.width(6.dp))
                    Text("Okamžitá stamina (klesá/roste)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9), fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFF59E0B).copy(alpha = 0.5f)))
                    Spacer(Modifier.width(6.dp))
                    Text("Potenciální strop", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B), fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Skutečná výdrž klesá při intenzitě nad prahem (sprinty, kopce) a zotavuje se zpět k potenciální výdrži při volnějším tempu.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
    }
}

/**
 * Garmin Zhodnocení výkonu / kondice (Performance Condition Hero Card).
 */
@Composable
fun GarminPerformanceConditionCard(
    condition: Int,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.PERFORMANCE_CONDITION),
            onDismiss = { showInfo = false }
        )
    }

    val condClamped = condition.coerceIn(-5, 5)
    val (label, desc, col) = when {
        condClamped >= 4 -> Triple("Vynikající (+${condClamped})", "Vaše srdeční frekvence je výrazně nižší než obvykle. Jste ve špičkové běžecké formě.", Color(0xFFA855F7))
        condClamped >= 1 -> Triple("Dobrý (+${condClamped})", "Běžecká ekonomika je nad vaším standardem. Tělo pracuje efektivně a šetří glykogen.", Color(0xFF10B981))
        condClamped == 0 -> Triple("Výchozí (0)", "Výkon a srdeční tep přesně odpovídají vaší dlouhodobé základní kondici VO2 Max.", Color(0xFF38BDF8))
        condClamped >= -2 -> Triple("Mírná únava (${condClamped})", "Srdeční frekvence je mírně zvýšená. Může se projevovat dehydratace nebo teplé počasí.", Color(0xFFF59E0B))
        else -> Triple("Únava / Přetížení (${condClamped})", "Výrazný srdeční drift a vysoká zátěž. Doporučeno volnější tempo a delší spánek.", Color(0xFFEF4444))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(col)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GARMIN ZHODNOCENÍ VÝKONU (KONDICE)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = col
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                Text(
                    if (condClamped > 0) "+$condClamped" else "$condClamped",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = col
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 2.dp))

            Spacer(Modifier.height(10.dp))

            // Stupnice od -5 do +5 s nulou uprostřed
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF1E293B))
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val midX = size.width / 2f
                    // Nulová ryska
                    drawLine(Color.White.copy(alpha = 0.5f), Offset(midX, 0f), Offset(midX, size.height), strokeWidth = 2f)

                    val frac = (condClamped / 5.0).coerceIn(-1.0, 1.0)
                    val barW = (midX * kotlin.math.abs(frac)).toFloat()
                    if (frac >= 0) {
                        drawRect(col, Offset(midX, 0f), Size(barW, size.height))
                    } else {
                        drawRect(col, Offset(midX - barW, 0f), Size(barW, size.height))
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-5 Únava", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFFEF4444))
                Text("0 Norma", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFF94A3B8))
                Text("+5 Špička", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFFA855F7))
            }
        }
    }
}

/**
 * Kardiovaskulární relativní úsilí (Suffer Score / Relativní zátěž srdce).
 * Počítá se lokálně z tepových zón Z1–Z5.
 */
@Composable
fun RelativeEffortCard(
    score: Int,
    hrZonesTimesS: List<Double>,
    totalTimeS: Double,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.RELATIVE_EFFORT),
            onDismiss = { showInfo = false }
        )
    }

    val (title, desc) = RunningDynamicsModel.stravaRelativeEffortDescription(score)
    val accentOrange = Color(0xFFF97316)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accentOrange.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF13131A))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "KARDIO ZÁTĚŽ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = accentOrange,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RELATIVNÍ ÚSILÍ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                Text(
                    "$score",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = accentOrange
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 2.dp))

            Spacer(Modifier.height(10.dp))

            // Rozpad minut v zónách Z1–Z5
            if (hrZonesTimesS.size >= 5) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    val zoneLabels = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
                    val zoneColors = listOf(Color(0xFF64748B), Color(0xFF38BDF8), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444))
                    for (i in 0 until 5) {
                        val minVal = (hrZonesTimesS.getOrElse(i) { 0.0 } / 60.0).toInt()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(zoneLabels[i], style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = zoneColors[i])
                            Text("$minVal m", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Zpětná kompatibilita pro stávající volání. */
@Composable
fun StravaRelativeEffortCard(score: Int, hrZonesTimesS: List<Double>, totalTimeS: Double, modifier: Modifier = Modifier) =
    RelativeEffortCard(score, hrZonesTimesS, totalTimeS, modifier)

/**
 * Odhadované nejlepší výkony (Best Efforts: 400 m, 1 km, 1 míle, 5 km).
 */
@Composable
fun BestEffortsCard(
    bestEfforts: List<BestEffort>,
    modifier: Modifier = Modifier,
) {
    if (bestEfforts.isEmpty()) return

    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.BEST_EFFORTS),
            onDismiss = { showInfo = false }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEvents,
                        contentDescription = null,
                        tint = Color(0xFFF97316),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ATLETICKÉ MILNÍKY (BEST EFFORTS)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF97316)
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                Text("${bestEfforts.size} milníků", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
            }

            Spacer(Modifier.height(10.dp))

            bestEfforts.forEachIndexed { idx, be ->
                val timeFmt = "%02d:%02d".format((be.timeS / 60).toInt(), (be.timeS % 60).toInt())
                val paceMin = (be.paceSPerKm / 60).toInt()
                val paceSec = (be.paceSPerKm % 60).toInt()
                val paceFmt = "%d:%02d /km".format(paceMin, paceSec)
                val (badgeBg, badgeFg) = when (idx) {
                    0 -> Color(0xFFF59E0B).copy(alpha = 0.22f) to Color(0xFFF59E0B)
                    1 -> Color(0xFF94A3B8).copy(alpha = 0.22f) to Color(0xFFCBD5E1)
                    2 -> Color(0xFFD97706).copy(alpha = 0.22f) to Color(0xFFFDBA74)
                    else -> Color(0xFF38BDF8).copy(alpha = 0.18f) to Color(0xFF38BDF8)
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(badgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "#${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = badgeFg,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(be.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "na ${"%.1f".format(be.startKm)} km · výškově ${if (be.elevationChangeM >= 0) "+%.0f".format(be.elevationChangeM) else "%.0f".format(be.elevationChangeM)} m",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(timeFmt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8))
                        Text(paceFmt, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                    }
                }
                if (idx < bestEfforts.size - 1) {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** Zpětná kompatibilita pro stávající volání. */
@Composable
fun StravaBestEffortsCard(bestEfforts: List<BestEffort>, modifier: Modifier = Modifier) =
    BestEffortsCard(bestEfforts, modifier)

/**
 * GAP (Grade Adjusted Pace / Tempo upravené o sklon dle Minettiho modelu).
 */
@Composable
fun GapCard(
    realPaceSPerKm: Double?,
    gapPaceSPerKm: Double?,
    ascentM: Double?,
    modifier: Modifier = Modifier,
) {
    if (realPaceSPerKm == null || gapPaceSPerKm == null) return

    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        TelemetryInfoDialog(
            content = getTelemetryTopicContent(TelemetryTopic.GAP),
            onDismiss = { showInfo = false }
        )
    }

    fun fmtP(p: Double): String {
        val m = (p / 60).toInt()
        val s = (p % 60).toInt()
        return "%d:%02d /km".format(m, s)
    }

    val deltaS = realPaceSPerKm - gapPaceSPerKm
    val deltaStr = if (deltaS > 0) "-%d s/km".format(deltaS.toInt()) else "+%d s/km".format(-deltaS.toInt())

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF97316))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GAP: TEMPO UPRAVENÉ O SKLON",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF97316)
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoBadgeButton(onClick = { showInfo = true })
                }
                Text(
                    deltaStr,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        fmtP(gapPaceSPerKm),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981)
                    )
                    Text("GAP Tempo (ekvivalent roviny)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        fmtP(realPaceSPerKm),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text("Reálné tempo (v terénu)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
            }

            Spacer(Modifier.height(6.dp))
            val ascStr = ascentM?.let { "+%.0f m".format(it) } ?: "převýšení"
            Text(
                "Minettiho model energetického výdeje: Běh do stoupání ($ascStr) stál tělo stejný energetický výdej, jako byste po rovině běželi tempem ${fmtP(gapPaceSPerKm)}.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
    }
}

private fun formatAerobicMessage(ae: Double): String = when {
    ae < 1.0 -> "Žádný výrazný aerobní tréninkový stimul."
    ae < 2.0 -> "Regenerační zátěž, napomáhá zotavení svalových vláken."
    ae < 3.0 -> "Udržování aerobní kapacity a kardiovaskulární kondice."
    ae < 4.0 -> "Výrazné budování aerobního základu a kapilární sítě."
    ae < 5.0 -> "Vysoce stimulující trénink posouvající laktátový práh a VO2 Max."
    else -> "Maximální aerobní přetížení (Overreaching), vyžaduje adekvátní odpočinek."
}

private fun formatAnaerobicMessage(an: Double): String = when {
    an < 1.0 -> "Žádný anaerobní stimul (rovnoměrné tempo bez prudkých zrychlení)."
    an < 2.0 -> "Mírný anaerobní stimul z krátkých stoupání či zrychlení."
    an < 3.0 -> "Udržování anaerobní kapacity a rychlostní vytrvalosti."
    an < 4.0 -> "Zlepšování schopnosti odbourávat laktát a tolerovat vysokou intenzitu."
    else -> "Maximální rozvoj anaerobní kapacity, síly a rychlosti (sprinty)."
}

private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
