package com.hereliesaz.capturetheflag.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
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
import com.hereliesaz.capturetheflag.commentary.Commentary
import com.hereliesaz.capturetheflag.data.GameBackend
import com.hereliesaz.capturetheflag.data.PlatformServices
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.onboarding.Onboarding
import com.hereliesaz.capturetheflag.rules.Briefing
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Honors
import com.hereliesaz.capturetheflag.rules.Most
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.Perks
import com.hereliesaz.capturetheflag.rules.Progression
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
    var busy by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("PICK A CITY")
        Text("We cut it in half. Someone lives on the wrong side.")
        OutlinedTextField(name, { name = it }, label = { Text("City or metro area") }, singleLine = true, enabled = busy == null)
        Button(enabled = name.isNotBlank() && busy == null, onClick = {
            val city = name.trim()
            busy = city
            error = null
            scope.launch {
                runCatching { backend.requestCity(city) }
                    .onSuccess { onPicked(city) }
                    .onFailure { error = it.message }
                busy = null
            }
        }) { Text("Enter") }
        busy?.let { OnboardingProgress(backend, it) }
        error?.let { Text(it) }
    }
}

/** First player in a new city: they watch it being surveyed. Takes minutes, happens once. */
@Composable
private fun OnboardingProgress(backend: GameBackend, city: String) {
    val state by backend.onboarding(city).collectAsState()
    when (val s = state) {
        is Onboarding.Working -> {
            Text("Nobody has played $city yet. Surveying it now. This happens once.", style = MaterialTheme.typography.labelMedium)
            Text(s.stage.label + if (s.of > 0) " · ${s.done} of ${s.of}" else "…")
        }
        else -> Text("…")
    }
}

private enum class Tab(val label: String) { STATUS("Status"), MAP("Map"), ROSTER("Roster"), ACT("Act"), RADIO("Radio"), RANKS("Ranks"), CHAT("Chat") }

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
    val radio by backend.commentary(city).collectAsState()
    LaunchedEffect(radio.lastOrNull()) { platform.showLiveFeed(radio.takeLast(5).reversed().map { it.text }) }
    DisposableEffect(city) { onDispose { platform.showLiveFeed(emptyList()) } }
    LaunchedEffect(city) {
        backend.pings(city).collect { p ->
            pings.add(0, p)
            Alerts.forPing(p, backend.me.value?.id)?.let { (t, b) -> platform.alert(t, b) }
        }
    }

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
    // Ten minutes left to report: one warning, timed from the deadline itself.
    LaunchedEffect(mine?.jailDeadline, mine?.reportedAt) {
        val d = mine?.jailDeadline ?: return@LaunchedEffect
        if (mine.reportedAt != null) return@LaunchedEffect
        val wait = d - Alerts.DEADLINE_WARNING - clock()
        if (wait > 0) delay(wait)
        if (d - clock() > 0) platform.alert("Ten minutes", "Get to the jail and stay put, or you're out for the round.")
    }
    // State-change alerts: you jailed or freed, your jail under attack, the round starting or ending.
    var seen by remember { mutableStateOf<Game?>(null) }
    LaunchedEffect(g) {
        seen?.let { before -> Alerts.forChange(before, g, me?.id, now).forEach { (t, b) -> platform.alert(t, b) } }
        seen = g
    }
    LaunchedEffect(fix) { fix?.let { if (mine != null) backend.reportLocation(city, it) } }

    Column(Modifier.fillMaxSize()) {
        RulesPanel(Briefing.forPlayer(g, me?.id, now, fix?.let { g.territory.ownerOf(it.point) }))
        radio.lastOrNull()?.let { Ticker(it.text) { tab = Tab.RADIO } }
        Column(Modifier.weight(1f).padding(16.dp)) {
            when (tab) {
                Tab.STATUS -> StatusTab(backend, g, mine, now, fix?.let { g.territory.ownerOf(it.point) }, pings, city, onLeave)
                Tab.ROSTER -> RosterTab(platform, g, mine)
                Tab.ACT -> ActTab(backend, platform, g, mine, city, pings, now)
                Tab.RADIO -> RadioTab(radio)
                Tab.MAP -> MapTab(g, mine, fix, pings, now)
                Tab.RANKS -> RanksTab(backend, g)
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
            item { Text("You: ${it.team} · ${it.role}" + when { it.disqualified -> " · DISQUALIFIED"; it.isJailed -> " · JAILED"; else -> "" }) }
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
        if (g.phase is GamePhase.Active || g.phase is GamePhase.Ended) {
            val mvps = Honors.mvps(g)
            val boards = Most.entries.map { it to Honors.board(g, it) }.filter { it.second.isNotEmpty() }
            if (mvps.isNotEmpty() || boards.isNotEmpty()) item { HorizontalDivider(); Text("HONORS", fontWeight = FontWeight.Bold) }
            mvps.forEach { (t, id) -> item { Text("MVP, $t: ${g.players[id]?.user?.displayName} · ${g.earned[id] ?: 0} pts · +${Honors.MVP_BONUS} at the whistle") } }
            items(boards) { (most, board) ->
                Column {
                    Text("${most.title} (+${most.bonus})", fontWeight = FontWeight.Bold)
                    Text(most.blurb, style = MaterialTheme.typography.labelSmall)
                    Text(board.joinToString("  ·  ") { (id, v) -> "${g.players[id]?.user?.displayName} $v" })
                }
            }
        }
        if (pings.isNotEmpty()) {
            item { HorizontalDivider(); Text("PINGS", fontWeight = FontWeight.Bold) }
            items(pings) { p ->
                val myPerks = mine?.let { Progression.perksFor(it.level) }
                val label = when (p.kind) {
                    PingKind.INCURSION -> "#${p.number}"
                    PingKind.TRACKING -> "trail"
                    PingKind.TRIPWIRE -> "TRIPWIRE"
                    PingKind.INTERROGATION -> "interrogated"
                }
                val who = p.identified?.displayName ?: "unknown intruder"
                val lvl = if (myPerks?.keenEye == true && p.subjectLevel != null) " · lv ${p.subjectLevel}" else ""
                val fuzz = if (p.radiusM > 0) " ±${p.radiusM.toInt()} m" else ""
                val decoy = if (mine?.id in p.decoyRevealedTo) " · DECOY" else ""
                Text("$label · $who$lvl$decoy · %.5f, %.5f".fmt(p.location.lat, p.location.lng) + fuzz)
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
                    Text(p.user.displayName + " · lv ${p.level}" + roleTag(p) + if (p.isJailed) " · jailed" else "")
                }
            }
        }
    }
}

@Composable
private fun ActTab(backend: GameBackend, platform: PlatformServices, g: Game, mine: Player?, city: String, pings: List<Ping>, now: Millis) {
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
                if (mine.isLeader && mine.team in g.flags && mine.team !in g.jails) JailForm { venue, address ->
                    scope.launch {
                        val photo = platform.takePhoto() ?: return@launch
                        val at = photo.exifLocation ?: photo.deviceFix?.point
                            ?: return@launch report(Verdict.Rejected("No location on photo"))
                        report(backend.placeJail(city, venue, address, at, photo))
                    }
                } else g.jails[mine.team]?.let { Text("Jail placed at ${it.venueName}.") }
            }
            is GamePhase.Active -> if (mine.isJailed) JailPanel(g, mine, now) else {
                g.jails[mine.team.opponent]?.let { Text("Enemy jail: ${it.venueName}, ${it.address}") }
                val held = g.team(mine.team).count { it.isJailed && !it.disqualified }
                mine.breakoutSince?.let {
                    Text("BREAKING OUT: hold the jail ${countdown(it + GameRules.JAILBREAK_HOLD - now)} more. Leave and it's over.", fontWeight = FontWeight.Bold)
                }
                if (held > 0 && mine.breakoutSince == null) Button(onClick = {
                    scope.launch { platform.takePhoto()?.let { report(backend.jailbreak(city, it)) } }
                }) { Text("Start jailbreak: photograph enemy jail, then hold ${GameRules.JAILBREAK_HOLD / GameRules.MINUTE} min ($held held)") }
                val decoysLeft = Progression.perksFor(mine.level).decoysPerGame - (g.decoysUsed[mine.id] ?: 0)
                if (decoysLeft > 0) OutlinedButton(enabled = !mine.isJailed, onClick = {
                    scope.launch {
                        val here = platform.location.value?.point ?: return@launch report(Verdict.Rejected("No location"))
                        report(backend.decoy(city, here))
                    }
                }) { Text("Send decoy from here ($decoysLeft left)") }
                val perks = Progression.perksFor(mine.level)
                val vanishesLeft = perks.vanishesPerGame - (g.vanishesUsed[mine.id] ?: 0)
                if (vanishesLeft > 0 && mine.id in g.incursions) OutlinedButton(onClick = {
                    scope.launch { report(backend.vanish(city)) }
                }) { Text("Vanish: skip next ping ($vanishesLeft left)") }
                val interrogationsLeft = perks.interrogationsPerGame - (g.interrogationsUsed[mine.id] ?: 0)
                if (interrogationsLeft > 0) {
                    pings.filter { it.kind == PingKind.INCURSION && it.subject in g.incursions }
                        .distinctBy { it.subject }.forEach { p ->
                            OutlinedButton(onClick = { scope.launch { report(backend.interrogate(city, p.subject)) } }) {
                                Text("Interrogate ${p.identified?.displayName ?: "intruder #${p.number}"} ($interrogationsLeft left)")
                            }
                        }
                }
                if (perks.bountyMultiplier > 1 && mine.id !in g.bounties) {
                    Text("Place a bounty (×${perks.bountyMultiplier}):")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.team(mine.team.opponent).forEach { e ->
                            FilterChip(selected = false, onClick = { scope.launch { report(backend.bounty(city, e.id)) } }, label = { Text(e.user.displayName) })
                        }
                    }
                }
                GameEngine.flagSense(g, mine.id)?.let { (c, r) ->
                    Text("Flag Sense: enemy flag within ${r.toInt()} m of %.5f, %.5f".fmt(c.lat, c.lng))
                }
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

/** A prisoner's whole world: where to go, how long is left, how long they've stood there. */
@Composable
private fun JailPanel(g: Game, me: Player, now: Millis) {
    val jail = g.jails[me.team.opponent]
    when {
        me.disqualified -> Text("Disqualified. You never reported. Nothing you do this round counts.")
        me.reportedAt != null -> Text("In jail. Frozen until a teammate breaks you out or the round ends.")
        else -> {
            Text("JAILED. Report to ${jail?.venueName ?: "the enemy jail"}${jail?.let { ", " + it.address } ?: ""}.")
            me.jailDeadline?.let { Text("Arrive and stay ${GameRules.JAIL_REPORT_HOLD / GameRules.MINUTE} min within ${countdown(it - now)} or be disqualified.") }
            me.reportingSince?.let { Text("Reporting: ${countdown(now - it)} of ${countdown(GameRules.JAIL_REPORT_HOLD)}") }
        }
    }
}

@Composable
private fun JailForm(onSubmit: (String, String) -> Unit) {
    var venue by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    Text("Now the jail. Public, in your territory, at least ${GameRules.JAIL_MIN_FROM_FLAG_M.toInt()} m from the flag. The enemy will know where it is.")
    OutlinedTextField(venue, { venue = it }, label = { Text("Jail venue") }, singleLine = true)
    OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true)
    Button(enabled = venue.isNotBlank() && address.isNotBlank(), onClick = { onSubmit(venue.trim(), address.trim()) }) {
        Text("Photograph jail & register")
    }
}

@Composable
private fun FlagForm(onSubmit: (String, FlagVenueKind, String) -> Unit) {
    var venue by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FlagVenueKind.PUBLIC_SPACE) }
    Text("Choose your flag: something already there that cannot move. A statue, a doorway, a mural. Stand at it and photograph it.")
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

@Composable
private fun RanksTab(backend: GameBackend, g: Game) {
    val ledger by backend.ledger.collectAsState()
    val me by backend.me.collectAsState()
    var cityBoard by remember { mutableStateOf(true) }
    val board = if (cityBoard) Leaderboard.city(ledger, g.city.id) else Leaderboard.global(ledger)
    val myPoints = me?.let { u -> Leaderboard.totals(ledger)[u.id] } ?: 0L
    val level = Progression.levelFor(myPoints)
    val next = Progression.nextMilestone(level)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title("LEVEL $level") }
        item { Text("$myPoints pts · ${Progression.pointsFor(level + 1) - myPoints} to level ${level + 1}") }
        item { Text("Next advantage at level $next" + if (Progression.powerAt(next) > 1) " · double" else "") }
        item { perkLines(Progression.perksFor(level)).forEach { Text(it) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = cityBoard, onClick = { cityBoard = true }, label = { Text(g.city.name) })
                FilterChip(selected = !cityBoard, onClick = { cityBoard = false }, label = { Text("Global") })
            }
        }
        if (board.isEmpty()) item { Text("No one has scored. Yet.") }
        items(board) { s ->
            val bold = if (s.user == me?.id) FontWeight.Bold else FontWeight.Normal
            Text("${s.rank}. ${backend.displayName(s.user)} · lv ${s.level} · ${s.points}", fontWeight = bold)
        }
    }
}

private fun perkLines(p: Perks): List<String> = buildList {
    val min = GameRules.MINUTE
    if (p.firstPingDelayMs > 0) add("Second ping delayed ${p.firstPingDelayMs / min} min")
    if (p.identityDelayPings > 0) add("Identity hidden ${p.identityDelayPings} extra pings")
    if (p.thresholdMs > 0) add("Threshold: ${p.thresholdMs / 1000} s on enemy ground before it counts")
    if (p.blurM > 0) add("Blur: pings off by up to ${p.blurM.toInt()} m")
    if (p.crowdFactor > 0) add("Crowd: blur ×${1 + p.crowdFactor} per unit of density above average")
    if (p.nightCoverDivisor > 0) add("Night Cover: fewer recipients 1–5am")
    if (p.shadowDivisor > 0) add("Shadow: fewer recipients always")
    if (p.vanishesPerGame > 0) add("Vanish ×${p.vanishesPerGame} per game")
    if (p.lastStandsPerGame > 0) add("Last Stand ×${p.lastStandsPerGame} per game")
    if (p.legacyCapLevel != null) add("Legacy: your value frozen at level ${p.legacyCapLevel}, −${(p.legacyDiscount * 100).toInt()}%")
    p.paroleMs?.let { add("Parole after ${it / GameRules.HOUR} h") }
    if (p.decoysPerGame > 0) add("${p.decoysPerGame} decoy pings per game")
    if (p.doppelgangerSteps > 0) add("Doppelgänger: decoys walk ${p.doppelgangerSteps} steps")
    if (p.proximityAlertM > 0) add("Alerted to intruders within ${p.proximityAlertM.toInt()} m")
    if (p.witnessM > 0) add("Witness: teammates within ${p.witnessM.toInt()} m share your alerts")
    if (p.bleWindowBonusMs > 0) add("Tag window +${p.bleWindowBonusMs / 1000} s")
    if (p.sharpLensM > 0) add("Sharp Lens: +${p.sharpLensM.toInt()} m tag tolerance")
    if (p.keenEye) add("Keen Eye: pings show intruder level")
    if (p.counterintelM > 0) add("Counterintel: decoys within ${p.counterintelM.toInt()} m exposed")
    if (p.interrogationsPerGame > 0) add("Interrogate ×${p.interrogationsPerGame} per game")
    if (p.bountyMultiplier > 1) add("Bounty: marked enemy worth ×${p.bountyMultiplier}")
    if (p.bloodhoundMs > 0) add("Bloodhound: follow intruders ${p.bloodhoundMs / 1000} s after a ping")
    if (p.tripwireM > 0) add("Tripwire: ${p.tripwireM.toInt()} m around your flag")
    if (p.flagSenseM > 0) add("Flag Sense: enemy flag within a ${p.flagSenseM.toInt()} m circle")
    if (p.standingWeight > 0) add("Standing: captain draw weight +${p.standingWeight}")
    if (p.mentorShare > 0) add("Mentor: nearby juniors earn +${(p.mentorShare * 100).toInt()}%")
    if (p.deliberateMs > 0) add("Deliberate: +${p.deliberateMs / min} min to place the flag")
    if (isEmpty()) add("No advantages yet.")
}

/** The rules that apply to you right now. Pinned above every tab; nothing else is shown. */
@Composable
private fun RulesPanel(rules: List<String>) {
    if (rules.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rules.forEach { Text("— $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** The newest line of the broadcast, one row, tap for the full feed. */
@Composable
private fun Ticker(text: String, onOpen: () -> Unit) {
    Text(
        "ON AIR  $text",
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** The whole broadcast, newest first. */
@Composable
private fun RadioTab(lines: List<Commentary>) {
    if (lines.isEmpty()) return Text("Dead air. Something will happen. It always does.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines.reversed()) { c -> Text(c.text) }
    }
}
