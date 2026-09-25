package com.hereliesaz.capturetheflag.chat

import com.hereliesaz.capturetheflag.model.CityId
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GameId
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
    /** Whether [user] may read and post in [channel]. [game] is the city's current game, if any. */
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
