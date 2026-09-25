package com.hereliesaz.capturetheflag.chat

import com.hereliesaz.capturetheflag.model.CityId
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GameId
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Team

/** Where a message lives. The key is stable and is what the backend stores threads under. */
sealed interface Channel {
    val key: String

    /** Everyone registered with the app who is looking at this city: players, foes, onlookers. */
    data class City(val city: CityId) : Channel {
        override val key get() = "city:$city"
    }

    /** One team's private room for one game. */
    data class TeamRoom(val game: GameId, val team: Team) : Channel {
        override val key get() = "team:$game:${team.name}"
    }

    /** Private 1:1 between teammates. Participants are stored sorted so both ends share a key. */
    class Direct private constructor(val game: GameId, val a: PlayerId, val b: PlayerId) : Channel {
        override val key get() = "dm:$game:$a:$b"
        override fun equals(other: Any?) = other is Direct && other.key == key
        override fun hashCode() = key.hashCode()

        companion object {
            fun of(game: GameId, x: PlayerId, y: PlayerId): Direct =
                if (x <= y) Direct(game, x, y) else Direct(game, y, x)
        }
    }
}

data class ChatMessage(
    val id: String,
    val channel: String,
    val from: PlayerId,
    val fromName: String,
    val body: String,
    val at: Millis,
)

object ChatAccess {
    /**
     * Cut off from the team: jailed, or standing on enemy ground. What they learn out there
     * leaves only when they make it home, or when they say it on a live stream everybody hears.
     * So they can't post anywhere (their team reads the city channel too), and can't read their
     * team room or DMs. The city channel they can still read. Onlookers are never cut off.
     */
    fun blackedOut(user: PlayerId, game: Game?): Boolean {
        if (game == null || game.phase !is GamePhase.Active) return false
        val p = game.players[user] ?: return false
        if (p.isJailed) return true
        val here = game.lastFix[user]?.point ?: return false
        return game.territory.ownerOf(here) == p.team.opponent
    }

    /** Whether [user] may post in [channel]. [game] is the city's current game, if any. */
    fun canPost(user: PlayerId, channel: Channel, game: Game?): Boolean = canUse(user, channel, game) && !blackedOut(user, game)

    /** Whether [user] may read [channel] right now. */
    fun canRead(user: PlayerId, channel: Channel, game: Game?): Boolean =
        canUse(user, channel, game) && (channel is Channel.City || !blackedOut(user, game))

    /** Whether [channel] is [user]'s at all, blackout aside. [game] is the city's current game, if any. */
    fun canUse(user: PlayerId, channel: Channel, game: Game?): Boolean = when (channel) {
        is Channel.City -> true
        is Channel.TeamRoom -> game?.id == channel.game && game.players[user]?.team == channel.team
        is Channel.Direct -> {
            val a = game?.players?.get(channel.a)
            val b = game?.players?.get(channel.b)
            game?.id == channel.game && user in setOf(channel.a, channel.b) &&
                a != null && b != null && a.team == b.team && channel.a != channel.b
        }
    }
}
