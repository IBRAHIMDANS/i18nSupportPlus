package utils

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.KeyRangesCalculator
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class Check(
    val elementRange: TextSpan,
    val key: FullKey,
    val expected: TextSpan,
    val resolved: List<Literal> = listOf(),
    val isQuoted: Boolean = true
)

internal class AnnotationHolderFacadeTest {

    private fun literalsList(vararg text: String): List<Literal> = text.map { Literal(it) }

    private fun rangesCalculator(textSpan: TextSpan, isQuoted: Boolean = true) = KeyRangesCalculator(textSpan, isQuoted)

    @Test
    fun measureTotallyResolvedKey() {
        val resolvedChecks = listOf(
            Check(
                TextSpan(19, 43),
                FullKey("sampla:ROOA.Kea1.kea31", Literal("sampla"), literalsList("ROOA", "Kea1", "kea31")),
                TextSpan(27, 42)
            ),
            Check(
                TextSpan(19, 36),
                FullKey("ROOB.Keb1.keb31", null, literalsList("ROOB", "Keb1", "keb31")),
                TextSpan(20, 35)
            ),
            Check(
                TextSpan(19, 55),
                FullKey(
                    "samplc:missinс.wholс.compositс.keс", Literal("samplc"), literalsList("missinс", "wholс", "compositс", "keс")
                ),
                TextSpan(27, 54)
            ),
            Check(
                TextSpan(19, 36),
                FullKey("ROOL.Kel1.kel31", null, literalsList("ROOL", "Kel1", "kel31")),
                TextSpan(19, 34),
                listOf(),
                false
            )
        )
        resolvedChecks.forEach {
            check -> assertEquals(check.expected, rangesCalculator(check.elementRange, check.isQuoted).compositeKeyFullBounds(check.key))
        }
    }

    @Test
    fun measureUnresolvedKey() {
        listOf(
            Check(
                TextSpan(26, 55),
                FullKey("sampld:ROOD.Ked1.missingKed", Literal("sampld"), literalsList("ROOD", "Ked1", "missingKed")),
                TextSpan(44, 54),
                literalsList("ROOD", "Ked1")
            ),
            Check(
                TextSpan(26, 56),
                FullKey("samplf:ROOF.Kef1.missinf.Kef", Literal("samplf"), literalsList("ROOF", "Kef1", "missinf", "Kef")),
                TextSpan(44, 55),
                literalsList("ROOF", "Kef1")
            ),
            Check(
                TextSpan(26, 61),
                FullKey("samplt:ROTT.Ket1.Ket2.missint.Ket", Literal("samplt"), literalsList("ROTT", "Ket1", "Ket2", "missint", "Ket")),
                TextSpan(49, 60),
                literalsList("ROTT", "Ket1", "Ket2")
            ),
            Check(
                TextSpan(26, 52),
                FullKey(
                    "samplg:ROOG.\${sub}.keg31",
                    Literal("samplg"),
                    listOf(Literal("ROOG"), Literal("Keg1", 6), Literal("keg31", 0), Literal("keg41"))
                ),
                TextSpan(46, 51),
                listOf(Literal("ROOG"), Literal("Keg1", 6), Literal("keg31", 0))
            ),
            Check(
                TextSpan(26, 68),
                FullKey(
                    "samplq:ROOT.Key1.\${unknownInCompileTime}",
                    Literal("samplq"),
                    listOf(Literal("ROOT"), Literal("Key1"), Literal("*", 23))
                ),
                TextSpan(44, 67),
                listOf(Literal("ROOT"), Literal("Key1"))
            ),
            Check(
                TextSpan(26, 56),
                FullKey("samplm:ROOM.Kem1.missinm.Kem", Literal("samplm"), literalsList("ROOM", "Kem1", "missinm", "Kem")),
                TextSpan(43, 54),
                literalsList("ROOM", "Kem1"),
                false
            )
        ).forEach {
            check -> assertEquals(check.expected, rangesCalculator(check.elementRange, check.isQuoted).unresolvedKey(check.key, check.resolved))
        }
    }

    @Test
    fun measureUnresolvedTemplateKey() {
        listOf(
            Check(
                TextSpan(26, 52),
                FullKey(
                    "sample:ROOT.\${sub}.key31",
                    Literal("sample"),
                    listOf(
                        Literal("ROOT"), Literal("Key1", 6), Literal("key3", 0), Literal("key31")
                    )
                ),
                TextSpan(0, 0),
                listOf(Literal("ROOT"), Literal("Key1", 6), Literal("key31"))
            )
        ).forEach {
            check -> assertTrue(rangesCalculator(check.elementRange).unresolvedKey(check.key, check.resolved).isEmpty)
        }
    }

    @Test
    fun measureUnresolvedFile() {
        listOf(
            Check(
                TextSpan(19, 43),
                FullKey("MissingFile:Ex.Sub.Val", Literal("MissingFile"), literalsList("Ex", "Sub", "Val")),
                TextSpan(20, 31)
            ),
            Check(
                TextSpan(19, 43),
                FullKey("\${fileExpr}:ROOT.Key1.Key31", Literal("sampli", 11), literalsList("ROOT", "Key1", "Key31")),
                TextSpan(20, 31)
            ),
            Check(
                TextSpan(19, 43),
                FullKey("\${fileExpr}:ROOT.Key1.Key31", Literal("*", 11), literalsList("ROOT", "Key1", "Key31")),
                TextSpan(20, 31)
            ),
            Check(
                TextSpan(19, 43),
                FullKey("Ex.Sub.Val", null, literalsList("Ex", "Sub", "Val")),
                TextSpan(20, 20)
            ),
            Check(
                TextSpan(19, 43),
                FullKey("MissingFilt:Ez.Suz.Vaz", Literal("MissingFilt"), literalsList("Ez", "Suz", "Vaz")),
                TextSpan(19, 30),
                listOf(),
                false
            )
        ).forEach {
            check -> assertEquals(check.expected, rangesCalculator(check.elementRange, check.isQuoted).unresolvedNs(check.key))
        }
    }
}