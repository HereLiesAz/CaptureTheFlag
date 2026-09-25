package com.hereliesaz.capturetheflag.data

import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.chat.ChatMessage
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the client asks of the server. The server runs [com.hereliesaz.capturetheflag.engine.GameEngine]
 * authoritatively; the client never decides outcomes. Transport (Firebase, Supabase, a Ktor
 * service…) is an implementation detail behind this interface.
 */
interface GameBackend {
    val me: StateFlow<User?>

    suspend fun register(displayName: String, selfieUri: String): User

    /** Starts a round in [cityName] if none is running, otherwise returns the current one. */
    suspend fun requestCity(cityName: String): Game

    fun game(cityName: String): StateFlow<Game?>

    suspend fun join(cityName: String): Verdict
    suspend fun appointCoCaptains(cityName: String, picks: Set<PlayerId>): Verdict
    suspend fun placeFlag(
        cityName: String,
        venueName: String,
        kind: FlagVenueKind,
        address: String,
        venue: GeoPoint,
        photo: PhotoEvidence,
    ): Verdict

    suspend fun captureFlag(cityName: String, photo: PhotoEvidence): Verdict
    suspend fun tag(cityName: String, target: PlayerId, photo: PhotoEvidence): Verdict

    suspend fun reportLocation(cityName: String, fix: LocationFix)

    /** Server-issued BLE token for this device to advertise right now. Rotates. */
    suspend fun currentBleToken(cityName: String): String

    /** Pings addressed to me. */
    fun pings(cityName: String): Flow<Ping>

    /** Sends a decoy ping from [at]. Requires the decoy perk. */
    suspend fun decoy(cityName: String, at: GeoPoint): Verdict

    /** Every point ever awarded. Standings and levels derive from it via [com.hereliesaz.capturetheflag.rules.Leaderboard]. */
    val ledger: StateFlow<List<Award>>

    /** Display name for any registered user, for leaderboards. */
    fun displayName(user: PlayerId): String

    fun messages(channel: Channel): StateFlow<List<ChatMessage>>
    suspend fun send(channel: Channel, body: String): Verdict
}
