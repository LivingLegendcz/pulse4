package app.pulse4.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.pulse4.sharing.Category

/**
 * Jednotný systém monoline vektorových ikon (24×24 dp, šířka tahu 2.0f, zaoblené konce)
 * pro konzistentní vizuální jazyk napříč všemi obrazovkami aplikace (Dnes, Metriky, Tělo,
 * Aktivity, Tréninky, Skupiny a Nastavení).
 *
 * Každá biometrická metrika, korelační scéna i přepínač jednotek má svou vlastní,
 * nezaměnitelnou siluetu bez duplicit a bez nechtěných plných ploch.
 */
object PulseIcons {

    private inline fun monolineIcon(
        name: String,
        strokeWidth: Float = 2.0f,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

    /** 1. HRV (Variabilita srdečního tepu rMSSD): Monoline srdce s autonomní rMSSD vlnou */
    val Hrv: ImageVector by lazy {
        monolineIcon("PulseHrv") {
            // Obrys horní části srdce přecházející do pulzní křivky
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 20.5f)
                 lineTo(4.8f, 13.4f)
                curveTo(2.4f, 11.0f, 2.4f, 7.1f, 4.8f, 4.9f)
                curveTo(7.1f, 2.8f, 10.2f, 3.3f, 12f, 5.6f)
                curveTo(13.8f, 3.3f, 16.9f, 2.8f, 19.2f, 4.9f)
                curveTo(21.6f, 7.1f, 21.6f, 11.0f, 19.2f, 13.4f)
                lineTo(12f, 20.5f)
                close()
            }
            // Vnitřní rMSSD křivka s uzlovými body
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.5f, 11.5f)
                lineTo(9.2f, 11.5f)
                lineTo(10.7f, 8.2f)
                lineTo(12.8f, 14.5f)
                lineTo(14.3f, 11.5f)
                lineTo(17.5f, 11.5f)
            }
        }
    }

    /** 2. Klidový tep (RHR / Heart Rate): Čisté obrysové srdce (žádná plná skvrna jako Icons.Outlined.Favorite) */
    val HeartRate: ImageVector by lazy {
        monolineIcon("PulseHeartRate") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 20.4f)
                lineTo(4.6f, 13.1f)
                curveTo(2.2f, 10.7f, 2.3f, 6.8f, 4.9f, 4.6f)
                curveTo(7.2f, 2.7f, 10.3f, 3.2f, 12f, 5.5f)
                curveTo(13.7f, 3.2f, 16.8f, 2.7f, 19.1f, 4.6f)
                curveTo(21.7f, 6.8f, 21.8f, 10.7f, 19.4f, 13.1f)
                lineTo(12f, 20.4f)
                close()
            }
            // Jemný vnitřní odlesk komory
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(7.5f, 7.2f)
                curveTo(6.3f, 8.0f, 5.9f, 9.6f, 6.5f, 10.9f)
            }
        }
    }

    /** 3. EKG (Elektrokardiogram QTc / Sinusový rytmus): Medicínská křivka P-Q-R-S-T v kruhovém prstenci */
    val Ecg: ImageVector by lazy {
        monolineIcon("PulseEcg") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2.2f, 12.5f)
                lineTo(6.2f, 12.5f)
                lineTo(7.8f, 10.2f)
                lineTo(9.2f, 14.2f)
                lineTo(11.4f, 4.2f)
                lineTo(13.6f, 19.2f)
                lineTo(15.4f, 10.8f)
                lineTo(17.0f, 12.5f)
                lineTo(21.8f, 12.5f)
            }
        }
    }

    /** 4. Spánek (Doba spánku & Noční obnova): Půlměsíc s polární hvězdou */
    val Sleep: ImageVector by lazy {
        monolineIcon("PulseSleep") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20.2f, 14.4f)
                curveTo(18.8f, 15.1f, 17.2f, 15.4f, 15.5f, 15.2f)
                curveTo(10.8f, 14.7f, 7.3f, 10.5f, 7.8f, 5.8f)
                curveTo(8.0f, 4.6f, 8.4f, 3.5f, 9.1f, 2.6f)
                curveTo(5.4f, 3.6f, 2.8f, 7.1f, 3.1f, 11.3f)
                curveTo(3.5f, 16.5f, 8.0f, 20.5f, 13.2f, 20.1f)
                curveTo(16.5f, 19.9f, 19.2f, 17.6f, 20.2f, 14.4f)
                close()
            }
            // Čtyřcípá hvězda
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(17.5f, 4.2f)
                lineTo(17.5f, 8.2f)
                moveTo(15.5f, 6.2f)
                lineTo(19.5f, 6.2f)
            }
        }
    }

    /** 5. Body Battery (Energetická rezerva): Čistý blesk uvnitř energetického článku */
    val BodyBattery: ImageVector by lazy {
        monolineIcon("PulseBodyBattery") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(13.2f, 2.8f)
                lineTo(5.8f, 13.2f)
                lineTo(11.8f, 13.2f)
                lineTo(10.8f, 21.2f)
                lineTo(18.2f, 10.8f)
                lineTo(12.2f, 10.8f)
                close()
            }
        }
    }

    /** 6. Stres (Autonomní zátěž): Harmonická vlna rovnováhy autonomního nervového systému */
    val Stress: ImageVector by lazy {
        monolineIcon("PulseStress") {
            // Vnější zenový kruh s přerušením
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3.2f)
                curveTo(16.9f, 3.2f, 20.8f, 7.1f, 20.8f, 12f)
                curveTo(20.8f, 16.9f, 16.9f, 20.8f, 12f, 20.8f)
                curveTo(7.1f, 20.8f, 3.2f, 16.9f, 3.2f, 12f)
                curveTo(3.2f, 7.1f, 7.1f, 3.2f, 12f, 3.2f)
            }
            // Vnitřní autonomní sinusoida
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.5f, 12f)
                curveTo(8.2f, 7.8f, 10.2f, 7.8f, 12f, 12f)
                curveTo(13.8f, 16.2f, 15.8f, 16.2f, 17.5f, 12f)
            }
        }
    }

    /** 7. Kroky (Denní pohyb & Chůze): Dynamická běžecká/chodecká silueta kroku */
    val Steps: ImageVector by lazy {
        monolineIcon("PulseSteps") {
            // Hlava
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(13.5f, 5.5f)
                curveTo(13.5f, 6.6f, 12.6f, 7.5f, 11.5f, 7.5f)
                curveTo(10.4f, 7.5f, 9.5f, 6.6f, 9.5f, 5.5f)
                curveTo(9.5f, 4.4f, 10.4f, 3.5f, 11.5f, 3.5f)
                curveTo(12.6f, 3.5f, 13.5f, 4.4f, 13.5f, 5.5f)
            }
            // Trup a krok
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7.5f, 12.5f)
                lineTo(10.5f, 9.2f)
                lineTo(13.8f, 10.2f)
                lineTo(16.8f, 12.5f)
                moveTo(11.5f, 9.6f)
                lineTo(10.2f, 15.0f)
                lineTo(7.2f, 20.2f)
                moveTo(10.8f, 14.5f)
                lineTo(14.5f, 16.5f)
                lineTo(16.2f, 20.5f)
            }
        }
    }

    /** 8. Tělesná hmotnost (Váha): Chytrá váha s ciferníkem (vyhrazená POUZE pro metriku Váha) */
    val Weight: ImageVector by lazy {
        monolineIcon("PulseWeight") {
            // Zaoblená deska chytré váhy
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 3.5f)
                lineTo(17f, 3.5f)
                curveTo(19.2f, 3.5f, 20.5f, 4.8f, 20.5f, 7f)
                lineTo(20.5f, 17f)
                curveTo(20.5f, 19.2f, 19.2f, 20.5f, 17f, 20.5f)
                lineTo(7f, 20.5f)
                curveTo(4.8f, 20.5f, 3.5f, 19.2f, 3.5f, 17f)
                lineTo(3.5f, 7f)
                curveTo(3.5f, 4.8f, 4.8f, 3.5f, 7f, 3.5f)
                close()
            }
            // Oblouk displeje váhy a ručička
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.0f, 11.2f)
                curveTo(9.2f, 8.2f, 14.8f, 8.2f, 16.0f, 11.2f)
                moveTo(12.0f, 11.5f)
                lineTo(13.8f, 8.8f)
            }
        }
    }

    /** 9. Svalová hmota (Svaly): Anatomický biceps / silová paže (namísto nejasné šikmé činky) */
    val Muscle: ImageVector by lazy {
        monolineIcon("PulseMuscle") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Zápěstí a pěst nahoře vlevo
                moveTo(6.5f, 9.5f)
                curveTo(5.2f, 8.2f, 5.2f, 6.0f, 6.8f, 4.8f)
                curveTo(8.2f, 3.8f, 10.0f, 4.6f, 10.2f, 6.4f)
                lineTo(9.5f, 10.2f)
                // Vrchol klenby bicepsu
                curveTo(11.5f, 8.2f, 15.2f, 8.4f, 17.2f, 10.6f)
                curveTo(19.2f, 12.8f, 19.0f, 16.5f, 16.5f, 18.4f)
                // Spodní linie paže a předloktí
                curveTo(13.8f, 20.2f, 8.8f, 20.0f, 6.2f, 17.4f)
                curveTo(4.6f, 15.8f, 4.8f, 12.2f, 6.5f, 9.5f)
                close()
            }
            // Vnitřní linie svalového vlákna
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(11.0f, 13.5f)
                curveTo(12.5f, 12.0f, 14.8f, 12.2f, 15.8f, 14.0f)
            }
        }
    }

    /** 10. Tělesný tuk (Tuk): Kaliper / tuková vrstva s energetickým jádrem (zcela odlišné od Vody!) */
    val BodyFat: ImageVector by lazy {
        monolineIcon("PulseBodyFat") {
            // Vnější silueta tukové buňky / energetické vrstvy
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3.0f)
                curveTo(16.5f, 6.8f, 19.5f, 10.4f, 19.5f, 14.2f)
                curveTo(19.5f, 18.2f, 16.2f, 21.0f, 12.0f, 21.0f)
                curveTo(7.8f, 21.0f, 4.5f, 18.2f, 4.5f, 14.2f)
                curveTo(4.5f, 10.4f, 7.5f, 6.8f, 12f, 3.0f)
                close()
            }
            // Vnitřní soustředné jádro (odlišuje tukovou tkáň od kapky vody)
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 10.5f)
                curveTo(14.1f, 10.5f, 15.8f, 12.2f, 15.8f, 14.3f)
                curveTo(15.8f, 16.4f, 14.1f, 18.0f, 12.0f, 18.0f)
                curveTo(9.9f, 18.0f, 8.2f, 16.4f, 8.2f, 14.3f)
                curveTo(8.2f, 12.2f, 9.9f, 10.5f, 12f, 10.5f)
                close()
            }
        }
    }

    /** 11. Tělesná voda (Hydratace / Voda): Čistá kapka s vodní hladinou uvnitř */
    val BodyWater: ImageVector by lazy {
        monolineIcon("PulseBodyWater") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2.8f)
                curveTo(12f, 2.8f, 5.2f, 10.2f, 5.2f, 14.6f)
                curveTo(5.2f, 18.4f, 8.2f, 21.2f, 12.0f, 21.2f)
                curveTo(15.8f, 21.2f, 18.8f, 18.4f, 18.8f, 14.6f)
                curveTo(18.8f, 10.2f, 12f, 2.8f, 12f, 2.8f)
                close()
            }
            // Vodní vlnka uvnitř kapky
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(7.8f, 15.2f)
                curveTo(9.4f, 13.8f, 10.8f, 13.8f, 12.0f, 15.0f)
                curveTo(13.2f, 16.2f, 14.6f, 16.2f, 16.2f, 14.8f)
            }
        }
    }

    /** 12. Kostní hmota (Kosti): Anatomická kost (namísto ořezávací ikony Crop!) */
    val BoneMass: ImageVector by lazy {
        monolineIcon("PulseBoneMass") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 6.2f)
                curveTo(7.2f, 4.5f, 4.8f, 4.8f, 4.5f, 6.8f)
                curveTo(4.2f, 8.2f, 5.2f, 9.2f, 6.2f, 9.5f)
                curveTo(4.8f, 10.2f, 4.5f, 12.2f, 6.5f, 12.8f)
                lineTo(11.2f, 17.5f)
                curveTo(11.8f, 19.5f, 13.8f, 19.2f, 14.5f, 17.8f)
                curveTo(14.8f, 18.8f, 15.8f, 19.8f, 17.2f, 19.5f)
                curveTo(19.2f, 19.2f, 19.5f, 16.8f, 17.8f, 15.5f)
                lineTo(8.5f, 6.2f)
                close()
            }
        }
    }

    /** 13. Složení těla (Body Composition / Withings Body Scan): Lidská postava ve skenovacím prstenci */
    val BodyComposition: ImageVector by lazy {
        monolineIcon("PulseBodyComposition") {
            // Hlava
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(14.0f, 5.2f)
                curveTo(14.0f, 6.3f, 13.1f, 7.2f, 12.0f, 7.2f)
                curveTo(10.9f, 7.2f, 10.0f, 6.3f, 10.0f, 5.2f)
                curveTo(10.0f, 4.1f, 10.9f, 3.2f, 12.0f, 3.2f)
                curveTo(13.1f, 3.2f, 14.0f, 4.1f, 14.0f, 5.2f)
            }
            // Postava a skenovací oblouky
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Ramena a paže
                moveTo(6.5f, 10.0f)
                lineTo(17.5f, 10.0f)
                // Trup
                moveTo(12.0f, 7.5f)
                lineTo(12.0f, 14.5f)
                // Nohy
                lineTo(9.2f, 20.5f)
                moveTo(12.0f, 14.5f)
                lineTo(14.8f, 20.5f)
            }
            // Boční bioimpedanční závorky
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4.8f, 7.5f)
                curveTo(3.2f, 10.5f, 3.2f, 14.5f, 4.8f, 17.5f)
                moveTo(19.2f, 7.5f)
                curveTo(20.8f, 10.5f, 20.8f, 14.5f, 19.2f, 17.5f)
            }
        }
    }

    /** 14. Beztuková hmota (FFM / Lean Body Mass): Štít jádra těla s jádrovou osou */
    val FatFreeMass: ImageVector by lazy {
        monolineIcon("PulseFatFreeMass") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3.0f)
                lineTo(19.2f, 6.2f)
                lineTo(19.2f, 11.8f)
                curveTo(19.2f, 16.2f, 16.0f, 19.6f, 12.0f, 21.0f)
                curveTo(8.0f, 19.6f, 4.8f, 16.2f, 4.8f, 11.8f)
                lineTo(4.8f, 6.2f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9.2f, 12.0f)
                lineTo(11.2f, 14.0f)
                lineTo(15.0f, 9.8f)
            }
        }
    }

    /** 15. Viscerální tuk: Terč břišního jádra se středovým bodem */
    val VisceralFat: ImageVector by lazy {
        monolineIcon("PulseVisceralFat") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(20.2f, 12.0f)
                curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12.0f, 20.2f)
                curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12.0f)
                curveTo(3.8f, 7.5f, 7.5f, 3.8f, 12.0f, 3.8f)
                curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12.0f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15.5f, 12.0f)
                curveTo(15.5f, 13.9f, 13.9f, 15.5f, 12.0f, 15.5f)
                curveTo(10.1f, 15.5f, 8.5f, 13.9f, 8.5f, 12.0f)
                curveTo(8.5f, 10.1f, 10.1f, 8.5f, 12.0f, 8.5f)
                curveTo(13.9f, 8.5f, 15.5f, 10.1f, 15.5f, 12.0f)
                moveTo(12.0f, 2.2f)
                lineTo(12.0f, 5.2f)
                moveTo(12.0f, 18.8f)
                lineTo(12.0f, 21.8f)
                moveTo(2.2f, 12.0f)
                lineTo(5.2f, 12.0f)
                moveTo(18.8f, 12.0f)
                lineTo(21.8f, 12.0f)
            }
        }
    }

    /** 16. Cévní věk (Vascular Age): Tepenné větvení v biologickém kruhu */
    val VascularAge: ImageVector by lazy {
        monolineIcon("PulseVascularAge") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20.2f, 12.0f)
                curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12.0f, 20.2f)
                curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12.0f)
                curveTo(3.8f, 7.5f, 7.5f, 3.8f, 12.0f, 3.8f)
                curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12.0f)
            }
            // Cévní větvení Y
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 6.8f)
                lineTo(12.0f, 12.2f)
                lineTo(8.5f, 16.2f)
                moveTo(12.0f, 12.2f)
                lineTo(15.5f, 16.2f)
            }
        }
    }

    /** 17. Rychlost pulzní vlny (PWV m/s): Rychlá šířící se arteriální vlna s vektorem */
    val PulseWaveVelocity: ImageVector by lazy {
        monolineIcon("PulsePwv") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.0f, 14.5f)
                curveTo(5.2f, 6.5f, 7.8f, 6.5f, 10.0f, 14.5f)
                curveTo(11.6f, 19.0f, 13.8f, 9.0f, 15.8f, 12.0f)
                lineTo(20.8f, 12.0f)
                moveTo(17.8f, 9.0f)
                lineTo(21.0f, 12.0f)
                lineTo(17.8f, 15.0f)
            }
        }
    }

    /** 18. Bazální metabolismus (BMR kcal): Čistý monoline plamen */
    val Bmr: ImageVector by lazy {
        monolineIcon("PulseBmr") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12.5f, 2.8f)
                curveTo(12.8f, 6.2f, 16.2f, 7.8f, 17.5f, 10.8f)
                curveTo(19.0f, 14.2f, 16.8f, 20.8f, 12.0f, 20.8f)
                curveTo(7.2f, 20.8f, 5.0f, 16.2f, 6.2f, 12.2f)
                curveTo(6.9f, 9.8f, 8.5f, 8.8f, 9.2f, 11.0f)
                curveTo(10.2f, 8.0f, 11.8f, 5.5f, 12.5f, 2.8f)
                close()
            }
        }
    }

    /** 19. Metabolický věk: Biologické přesýpací hodiny / cyklus */
    val MetabolicAge: ImageVector by lazy {
        monolineIcon("PulseMetabolicAge") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.5f, 3.5f)
                lineTo(17.5f, 3.5f)
                moveTo(6.5f, 20.5f)
                lineTo(17.5f, 20.5f)
                moveTo(7.5f, 3.5f)
                curveTo(7.5f, 8.2f, 12.0f, 10.2f, 12.0f, 12.0f)
                curveTo(12.0f, 13.8f, 7.5f, 15.8f, 7.5f, 20.5f)
                moveTo(16.5f, 3.5f)
                curveTo(16.5f, 8.2f, 12.0f, 10.2f, 12.0f, 12.0f)
                curveTo(12.0f, 13.8f, 16.5f, 15.8f, 16.5f, 20.5f)
            }
        }
    }

    /** 20. Okysličení krve (SpO2 %): Molekulární bubliny kyslíku O2 */
    val SpO2: ImageVector by lazy {
        monolineIcon("PulseSpO2") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round
            ) {
                // Hlavní kruh O
                moveTo(14.8f, 11.2f)
                curveTo(14.8f, 14.3f, 12.3f, 16.8f, 9.2f, 16.8f)
                curveTo(6.1f, 16.8f, 3.6f, 14.3f, 3.6f, 11.2f)
                curveTo(3.6f, 8.1f, 6.1f, 5.6f, 9.2f, 5.6f)
                curveTo(12.3f, 5.6f, 14.8f, 8.1f, 14.8f, 11.2f)
                // Menší kruh 2
                moveTo(20.4f, 15.8f)
                curveTo(20.4f, 17.8f, 18.8f, 19.4f, 16.8f, 19.4f)
                curveTo(14.8f, 19.4f, 13.2f, 17.8f, 13.2f, 15.8f)
                curveTo(13.2f, 13.8f, 14.8f, 12.2f, 16.8f, 12.2f)
                curveTo(18.8f, 12.2f, 20.4f, 13.8f, 20.4f, 15.8f)
            }
        }
    }

    /** 21. Dechová frekvence (Respiration brpm): Plicní laloky a průdušnice */
    val Respiration: ImageVector by lazy {
        monolineIcon("PulseRespiration") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Průdušnice
                moveTo(12.0f, 3.2f)
                lineTo(12.0f, 10.5f)
                lineTo(9.5f, 13.0f)
                moveTo(12.0f, 10.5f)
                lineTo(14.5f, 13.0f)
                // Levá plíce
                moveTo(9.8f, 7.2f)
                curveTo(6.2f, 7.2f, 3.5f, 10.8f, 3.8f, 15.8f)
                curveTo(4.0f, 18.5f, 6.2f, 19.8f, 8.8f, 18.8f)
                curveTo(10.0f, 18.2f, 10.2f, 16.5f, 10.2f, 14.0f)
                close()
                // Pravá plíce
                moveTo(14.2f, 7.2f)
                curveTo(17.8f, 7.2f, 20.5f, 10.8f, 20.2f, 15.8f)
                curveTo(20.0f, 18.5f, 17.8f, 19.8f, 15.2f, 18.8f)
                curveTo(14.0f, 18.2f, 13.8f, 16.5f, 13.8f, 14.0f)
                close()
            }
        }
    }

    /** 22. Fyzikální jednotky (kg, bpm, ms): Kalibrované měřidlo (nekoliduje s ikonou Váhy!) */
    val UnitPhysical: ImageVector by lazy {
        monolineIcon("PulseUnitPhysical") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 8.5f)
                lineTo(20.5f, 8.5f)
                lineTo(20.5f, 15.5f)
                lineTo(3.5f, 15.5f)
                close()
                moveTo(7.5f, 8.5f)
                lineTo(7.5f, 12.0f)
                moveTo(12.0f, 8.5f)
                lineTo(12.0f, 13.2f)
                moveTo(16.5f, 8.5f)
                lineTo(16.5f, 12.0f)
            }
        }
    }

    /** 23. Procentuální / Relativní škála (%): Čistý symbol procenta */
    val UnitPercent: ImageVector by lazy {
        monolineIcon("PulseUnitPercent") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(18.5f, 5.5f)
                lineTo(5.5f, 18.5f)
                // Horní kroužek
                moveTo(9.8f, 7.2f)
                curveTo(9.8f, 8.6f, 8.6f, 9.8f, 7.2f, 9.8f)
                curveTo(5.8f, 9.8f, 4.6f, 8.6f, 4.6f, 7.2f)
                curveTo(4.6f, 5.8f, 5.8f, 4.6f, 7.2f, 4.6f)
                curveTo(8.6f, 4.6f, 9.8f, 5.8f, 9.8f, 7.2f)
                // Dolní kroužek
                moveTo(19.4f, 16.8f)
                curveTo(19.4f, 18.2f, 18.2f, 19.4f, 16.8f, 19.4f)
                curveTo(15.4f, 19.4f, 14.2f, 18.2f, 14.2f, 16.8f)
                curveTo(14.2f, 15.4f, 15.4f, 14.2f, 16.8f, 14.2f)
                curveTo(18.2f, 14.2f, 19.4f, 15.4f, 19.4f, 16.8f)
            }
        }
    }
}

/** Sjednocená ikona pro 6 kategorií sdílení (Skupiny & Globální Sdílení v Nastavení). */
fun Category.unifiedIcon(): ImageVector = when (this) {
    Category.SLEEP -> PulseIcons.Sleep
    Category.HRV -> PulseIcons.Hrv
    Category.BODY_BATTERY -> PulseIcons.BodyBattery
    Category.HEART_RATE -> PulseIcons.HeartRate
    Category.ACTIVITY -> PulseIcons.Steps
    Category.BODY_COMPOSITION -> PulseIcons.BodyComposition
}

/** Sjednocená akcentová barva pro 6 kategorií sdílení. */
fun Category.unifiedColor(): Color = when (this) {
    Category.SLEEP -> Color(0xFF818CF8)
    Category.HRV -> Color(0xFF2DD4BF)
    Category.BODY_BATTERY -> Color(0xFFF59E0B)
    Category.HEART_RATE -> Color(0xFFFB7185)
    Category.ACTIVITY -> Color(0xFF38BDF8)
    Category.BODY_COMPOSITION -> Color(0xFF10B981)
}
