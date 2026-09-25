package com.hereliesaz.capturetheflag.commentary

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.PlayerId

/** Who has jailed whom, between two players, across every round on record. */
data class HeadToHead(val aJailedB: Int = 0, val bJailedA: Int = 0) {
    val total get() = aJailedB + bJailedA
    val isRivalry get() = total >= 2 || (aJailedB >= 1 && bJailedA >= 1)

    companion object {
        val NONE = HeadToHead()

        /**
         * Reads tags off the ledger. A tag writes two awards at the same instant in the same round:
         * the tagger's "Jailed <name>" and the prisoner's "Jailed". Pairing them gives who got whom.
         */
        fun between(ledger: List<Award>, a: PlayerId, b: PlayerId): HeadToHead {
            val prisonerAwards = ledger.filter { it.reason == "Jailed" && (it.user == a || it.user == b) }
                .associateBy { Triple(it.game, it.at, it.user) }
            var ab = 0
            var ba = 0
            ledger.filter { it.points > 0 && it.reason.startsWith("Jailed ") && (it.user == a || it.user == b) }.forEach { t ->
                val other = if (t.user == a) b else a
                if (prisonerAwards.containsKey(Triple(t.game, t.at, other))) if (t.user == a) ab++ else ba++
            }
            return HeadToHead(ab, ba)
        }
    }
}
