package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.TeamAssignment
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeadershipTest {
    private val users = (1..10).map { User("u$it", "U$it", "s") }

    @Test fun rookiesNeverCaptainWhileAVeteranIsPlaying() = repeat(200) { seed ->
        // Two veterans among ten: dealt first, so one lands on each team, and each captains.
        val veterans = setOf("u3", "u8")
        val players = TeamAssignment.assign(users, Random(seed), isRookie = { it !in veterans })
        val captains = players.values.filter { it.role == Role.CAPTAIN }
        assertEquals(2, captains.size)
        assertEquals(veterans, captains.map { it.id }.toSet())
    }

    @Test fun anAllRookieGameStillGetsCaptains() {
        val players = TeamAssignment.assign(users, Random(1), isRookie = { true })
        assertEquals(2, players.values.count { it.role == Role.CAPTAIN })
    }

    @Test fun rookiesCantBeNamedCoCaptainUnlessEveryoneIsOne() {
        val players = TeamAssignment.assign(users, Random(2), isRookie = { it != "u1" && it != "u2" && it != "u3" })
        val captain = players.values.first { it.role == Role.CAPTAIN }
        val rookieMate = players.values.first { it.team == captain.team && it.rookie }
        assertNull(TeamAssignment.appointCoCaptains(players, captain.id, setOf(rookieMate.id)))
        val allRookies = TeamAssignment.assign(users, Random(2), isRookie = { true })
        val c = allRookies.values.first { it.role == Role.CAPTAIN }
        val mate = allRookies.values.first { it.team == c.team && it.id != c.id }
        assertNotNull(TeamAssignment.appointCoCaptains(allRookies, c.id, setOf(mate.id)))
        assertTrue(allRookies.values.all { it.rookie })
    }
}
