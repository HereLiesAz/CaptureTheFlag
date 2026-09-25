package com.hereliesaz.capturetheflag.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.data.GameBackend
import com.hereliesaz.capturetheflag.data.PlatformServices
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Root. Registration → city → game. */
@Composable
fun App(backend: GameBackend, platform: PlatformServices, clock: () -> Millis) = CtfTheme {
    val me by backend.me.collectAsState()
    var city by remember { mutableStateOf<String?>(null) }
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                me == null -> RegisterScreen(backend, platform)
                city == null -> CityScreen(backend) { city = it }
                else -> GameScreen(backend, platform, clock, city!!) { city = null }
            }
        }
    }
}

@Composable
private fun RegisterScreen(backend: GameBackend, platform: PlatformServices) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selfie by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("CAPTURE THE FLAG")
        Text("Your face goes on the roster. The other side sees it. That is the point.")
        platform.Portrait(selfie, Modifier.size(160.dp))
        OutlinedButton(onClick = { scope.launch { selfie = platform.takeSelfie() ?: selfie } }) {
            Text(if (selfie == null) "Take selfie" else "Retake")
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        Button(
            enabled = name.isNotBlank() && selfie != null,
            onClick = { scope.launch { backend.register(name.trim(), selfie!!) } },
        ) { Text("Register") }
    }
}

@Composable
private fun CityScreen(backend: GameBackend, onPicked: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("PICK A CITY")
        Text("We cut it in half. Someone lives on the wrong side.")
        OutlinedTextField(name, { name = it }, label = { Text("City or metro area") }, singleLine = true)
        Button(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                runCatching { backend.requestCity(name.trim()) }
                    .onSuccess { onPicked(name.trim()) }
                    .onFailure { error = it.message }
            }
        }) { Text("Enter") }
        error?.let { Text(it) }
    }
}

private enum class Tab(val label: String) { STATUS("Status"), ROSTER("Roster"), ACT("Act"), CHAT("Chat") }

@Composable
private fun GameScreen(
    backend: GameBackend,
    platform: PlatformServices,
    clock: () -> Millis,
    city: String,
    onLeave: () -> Unit,
) {
    val game by backend.game(city).collectAsState()
    val me by backend.me.collectAsState()
    val fix by platform.location.collectAsState()
    var tab by remember { mutableStateOf(Tab.STATUS) }
    var now by remember { mutableStateOf(clock()) }
    val pings = remember { mutableStateListOf<Ping>() }

    LaunchedEffect(Unit) { while (true) { now = clock(); delay(1_000) } }
    LaunchedEffect(city) { backend.pings(city).collect { pings.add(0, it) } }

    val g = game ?: return Text("Loading…", Modifier.padding(24.dp))
    val mine = me?.let { g.players[it.id] }

    // Live play: stream location and keep BLE proximity advertising a fresh token.
    LaunchedEffect(g.phase is GamePhase.Active, mine?.id) {
        if (g.phase is GamePhase.Active && mine != null) {
            platform.startTracking(city)
            while (true) {
                platform.startProximity(backend.currentBleToken(city))
                delay(GameRules.BLE_TOKEN_ROTATION / 3)
            }
        } else {
            platform.stopTracking(); platform.stopProximity()
        }
    }
    LaunchedEffect(fix) { fix?.let { if (mine != null) backend.reportLocation(city, it) } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(16.dp)) {
            when (tab) {
                Tab.STATUS -> StatusTab(backend, g, mine, now, fix?.let { g.territory.ownerOf(it.point) }, pings, city, onLeave)
                Tab.ROSTER -> RosterTab(platform, g, mine)
                Tab.ACT -> ActTab(backend, platform, g, mine, city)
                Tab.CHAT -> ChatTab(backend, g, mine, city)
            }
        }
        NavigationBar {
            Tab.entries.forEach {
                NavigationBarItem(selected = tab == it, onClick = { tab = it }, icon = {}, label = { Text(it.label) })
            }
        }
    }
}

@Composable
private fun StatusTab(
    backend: GameBackend,
    g: Game,
    mine: Player?,
    now: Millis,
    standingIn: com.hereliesaz.capturetheflag.model.Team?,
    pings: List<Ping>,
    city: String,
    onLeave: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title(g.city.name.uppercase()) }
        item {
            Text(
                when (val p = g.phase) {
                    is GamePhase.Signup -> "Sign-up closes in ${countdown(p.deadline - now)} · ${g.signups.size} in"
                    is GamePhase.FlagPlacement -> "Leaders placing flags · ${countdown(p.deadline - now)}"
                    is GamePhase.Active -> "Live · tie called in ${countdown(p.deadline - now)}"
                    is GamePhase.Ended -> "Over · " + when (val o = p.outcome) {
                        is Outcome.FlagCaptured -> "${o.winner} took the flag"
                        is Outcome.Forfeit -> "${o.loser} forfeits: ${o.reason}"
                        Outcome.Tie -> "Tie"
                        Outcome.Cancelled -> "Not enough players"
                    }
                },
            )
        }
        mine?.let {
            item { Text("You: ${it.team} · ${it.role}" + if (it.isJailed) " · JAILED" else "") }
            item { Text(if (standingIn == null) "Outside the city" else if (standingIn == it.team) "Home ground" else "ENEMY GROUND. They know.") }
        }
        if (g.phase is GamePhase.Signup && g.signups.none { it.id == backend.me.value?.id }) {
            item { Button(onClick = { scope.launch { msg = (backend.join(city) as? Verdict.Rejected)?.reason } }) { Text("Sign up") } }
        }
        if (g.phase is GamePhase.Ended) {
            item { Button(onClick = { scope.launch { backend.requestCity(city) } }) { Text("Next round") } }
        }
        item { OutlinedButton(onClick = onLeave) { Text("Change city") } }
        msg?.let { item { Text(it) } }
        if (pings.isNotEmpty()) {
            item { HorizontalDivider(); Text("PINGS", fontWeight = FontWeight.Bold) }
            items(pings) { p ->
                Text("#${p.number} · ${p.identified?.displayName ?: "unknown intruder"} · %.5f, %.5f".fmt(p.location.lat, p.location.lng))
            }
        }
    }
}

@Composable
private fun RosterTab(platform: PlatformServices, g: Game, mine: Player?) {
    val teams = mine?.let { listOf(it.team, it.team.opponent) } ?: com.hereliesaz.capturetheflag.model.Team.entries
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (g.players.isEmpty()) item { Text("Teams are dealt when sign-up closes.") }
        teams.forEach { t ->
            item { Title(if (t == mine?.team) "$t · YOURS" else "$t") }
            items(g.team(t).sortedBy { it.role.ordinal.let { r -> if (r == 0) 3 else r } }) { p ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    platform.Portrait(p.user.selfieUrl, Modifier.size(56.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(p.user.displayName + roleTag(p) + if (p.isJailed) " · jailed" else "")
                }
            }
        }
    }
}

@Composable
private fun ActTab(backend: GameBackend, platform: PlatformServices, g: Game, mine: Player?, city: String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    fun report(v: Verdict) { result = when (v) { Verdict.Valid -> "Confirmed."; is Verdict.Rejected -> v.reason } }

    if (mine == null) return Text("Not playing this round.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (g.phase) {
            is GamePhase.FlagPlacement -> {
                if (mine.role == Role.CAPTAIN) CoCaptainPicker(g, mine) { picks ->
                    scope.launch { report(backend.appointCoCaptains(city, picks)) }
                }
                if (mine.isLeader && mine.team !in g.flags) FlagForm { venue, kind, address ->
                    scope.launch {
                        val photo = platform.takePhoto() ?: return@launch
                        val at = photo.exifLocation ?: photo.deviceFix?.point
                            ?: return@launch report(Verdict.Rejected("No location on photo"))
                        report(backend.placeFlag(city, venue, kind, address, at, photo))
                    }
                } else Text(g.flags[mine.team]?.let { "Flag placed at ${it.venueName}." } ?: "Waiting on your leaders.")
            }
            is GamePhase.Active -> {
                Button(enabled = !mine.isJailed, onClick = {
                    scope.launch { platform.takePhoto()?.let { report(backend.captureFlag(city, it)) } }
                }) { Text("Photograph enemy flag") }
                Text("Tag an intruder — pick who you photographed:")
                LazyColumn {
                    items(g.team(mine.team.opponent).filterNot { it.isJailed }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !mine.isJailed) {
                                scope.launch { platform.takePhoto()?.let { report(backend.tag(city, p.id, it)) } }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            platform.Portrait(p.user.selfieUrl, Modifier.size(44.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(p.user.displayName)
                        }
                    }
                }
            }
            else -> Text("Nothing to do yet.")
        }
        result?.let { Text(it, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CoCaptainPicker(g: Game, captain: Player, onSave: (Set<String>) -> Unit) {
    val picks = remember { mutableStateListOf<String>().apply { addAll(g.team(captain.team).filter { it.role == Role.CO_CAPTAIN }.map { it.id }) } }
    Text("Co-captains (up to ${GameRules.MAX_CO_CAPTAINS})")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.team(captain.team).filter { it.id != captain.id }.forEach { p ->
            FilterChip(
                selected = p.id in picks,
                onClick = { if (p.id in picks) picks.remove(p.id) else if (picks.size < GameRules.MAX_CO_CAPTAINS) picks.add(p.id) },
                label = { Text(p.user.displayName) },
            )
        }
    }
    OutlinedButton(onClick = { onSave(picks.toSet()) }) { Text("Appoint") }
}

@Composable
private fun FlagForm(onSubmit: (String, FlagVenueKind, String) -> Unit) {
    var venue by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FlagVenueKind.PUBLIC_SPACE) }
    Text("Place your flag. Stand at it. It does not move for seven days.")
    OutlinedTextField(venue, { venue = it }, label = { Text("Venue name") }, singleLine = true)
    OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FlagVenueKind.entries.forEach {
            FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it.name.lowercase().replace('_', ' ')) })
        }
    }
    Button(enabled = venue.isNotBlank() && address.isNotBlank(), onClick = { onSubmit(venue.trim(), kind, address.trim()) }) {
        Text("Photograph flag & register")
    }
}

@Composable
private fun ChatTab(backend: GameBackend, g: Game, mine: Player?, city: String) {
    val scope = rememberCoroutineScope()
    val channels = buildList {
        add("City" to Channel.City(g.city.id))
        mine?.let { m ->
            add("Team" to Channel.TeamRoom(g.id, m.team))
            g.team(m.team).filter { it.id != m.id }.forEach { add(it.user.displayName to Channel.Direct.of(g.id, m.id, it.id)) }
        }
    }
    var selected by remember { mutableStateOf(0) }
    val channel = channels[selected.coerceIn(channels.indices)].second
    val messages by backend.messages(channel).collectAsState()
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            channels.forEachIndexed { i, (label, _) ->
                FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text(label) })
            }
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { m ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(m.fromName, style = MaterialTheme.typography.labelSmall)
                    Text(m.body)
                }
            }
        }
        error?.let { Text(it) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Say something you'll regret") })
            Spacer(Modifier.size(8.dp))
            Button(onClick = {
                scope.launch {
                    val v = backend.send(channel, draft)
                    error = (v as? Verdict.Rejected)?.reason
                    if (v == Verdict.Valid) draft = ""
                }
            }) { Text("Send") }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)

private fun roleTag(p: Player) = when (p.role) {
    Role.CAPTAIN -> " · captain"
    Role.CO_CAPTAIN -> " · co-captain"
    Role.PLAYER -> ""
}

private fun countdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val d = s / 86_400; val h = s % 86_400 / 3600; val m = s % 3600 / 60; val sec = s % 60
    return (if (d > 0) "${d}d " else "") + "${h.pad()}:${m.pad()}:${sec.pad()}"
}

private fun Long.pad() = toString().padStart(2, '0')

/** Minimal multiplatform `%.5f` formatting. */
private fun String.fmt(vararg xs: Double): String {
    var i = 0
    return Regex("%\\.(\\d)f").replace(this) { mr ->
        val digits = mr.groupValues[1].toInt()
        val x = xs[i++]
        val scale = (1..digits).fold(1L) { acc, _ -> acc * 10 }
        val r = kotlin.math.round(kotlin.math.abs(x) * scale).toLong()
        (if (x < 0) "-" else "") + "${r / scale}." + (r % scale).toString().padStart(digits, '0')
    }
}
