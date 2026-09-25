# Capture the Flag

A city, cut in half. Two teams. Seven days. One photograph ends it.

## Rules as implemented

| Rule | Where |
|---|---|
| City split by a straight line, chosen at random from the fairest candidates. Weighted: population 0.4, geography 0.3 (cuts that follow rivers/highways score better), buildings 0.2, land 0.1 | `rules/CityPartitioner.kt` |
| 24 h sign-up → 1 h flag placement → 7 days of play → tie. Next round starts on request after any ending | `engine/GameEngine.kt`, `rules/GameRules.kt` |
| Fewer than 2 sign-ups: round cancelled | `GameEngine.closeSignup` |
| Random, size-balanced teams; one random captain each; captain names up to 2 co-captains | `rules/TeamAssignment.kt` |
| Leaders register venue (public space / public building / business), address, and a flag photo with GPS EXIF. Venue must sit inside the team's own territory | `Verification.flagRegistration` |
| Leaders also register a jail: public, own territory, flag first, at least 400 m from it. Public to both teams. Missing flag or jail at the deadline forfeits | `Verification.jailRegistration` |
| Win: an opponent photographs the flag within 40 m of its registered location | `Verification.flagCapture` |
| Jail: photo of an opponent standing in your territory. Photo EXIF must match the target's last fix (60 m), and the target's BLE token must have been heard within 60 s | `Verification.tag` |
| Incursion pings at entry, +60, +30, +15, +10, +5, then every 5 min. Each goes to a fresh random third (rounded up) of the opposing team. Identity attached from ping 6 | `rules/PingSchedule.kt` |
| Chat: city-wide (anyone registered, onlookers included), private team room, teammate-only DMs | `chat/Chat.kt` |

Every photo is also checked for freshness (2 min), and its EXIF location against the phone's live fused fix (60 m) to catch doctored metadata.

## Briefing

A rules panel sits above every tab and shows only what applies to the player right now (`rules/Briefing.kt`): sign-up, leader or follower during placement, home ground, enemy ground (with the next ping and when identity is revealed), breaking out, en route to jail, frozen, disqualified, or spectating. Everything else stays hidden.

## Radio

A live play-by-play in a radio sportscaster's voice (`commentary/Commentator.kt`), in the Radio tab, as a one-line ticker above every tab, and as an ongoing notification (shared with the location service's, so there is only ever one).

It narrates as it happens, by name and by the minute: who crossed and how long they've been over, the ping that gives them away, check-ins, breakout attempts starting and failing, tags with the report deadline, jailbreaks naming who walked, captures, the clock at 24 h and 1 h, and colour commentary after an hour of quiet.

It reads the play and guesses intent (`speculation`): an intruder steadily closing on the enemy flag ("my gut says they're hunting the flag"), drifting toward a jail with teammates inside ("that's a rescue run"), or backing off. It reads the hunters too: a defender who has had the pings on an intruder and keeps closing is called as a hunt. Guesses compare distances over time and say what someone wants, never where they are.

Rivalries come from shared history (`commentary/Rivalry.kt`): every tag pairs a tagger with a prisoner on the ledger, so the booth knows who has jailed whom, how often, and who's ahead. It calls the score on a tag ("leads that rivalry 3 to 1 now", "that squares it"), flags a nemesis when an old rival crosses into their territory, and turns a hunt between two rivals into a grudge match.

It brings up careers (`commentary/Career.kt`, read off the ledger): rookies, past captures and where, career tags, longest survival, biggest breakout, disqualifications, wins, high levels, winning and losing streaks, and how fast someone has climbed (or hasn't). Sometimes, not every line. Level-ups are called live as points land, with the big call when a milestone brings a new advantage.

What it never says: coordinates, distances, the flag's venue or address.

## Jail

Jail is conceptual, but the report is not.

1. **Tagged.** The prisoner gets a deadline to reach the enemy jail, set by how long the trip takes from where they were caught: the faster of walking and transit, times a weather factor (snow, ice, heat), ×1.25, +10 min, at least 15 min, plus the 5 min report.
2. **Report.** Stand within 40 m of the jail for 5 unbroken minutes before the deadline. Stepping away restarts the clock. After that they may leave.
3. **Frozen.** From the tag until release, a prisoner earns no points and cannot capture, tag, or break anyone out.
4. **Disqualified.** Miss the deadline and they are out for the round: still frozen, never freed, and every point they earned this round is taken back.
5. **Jailbreak.** A free teammate photographs the enemy jail (40 m, same checks as a flag capture), then holds it: 15 unbroken minutes within 40 m. Leaving, or being jailed, ends the attempt. They are on enemy ground the whole time, so their incursion pings keep running. When the hold completes, every jailed teammate who is not disqualified goes free, reported or still en route. The rescuer earns 20 per player freed.
6. **Parole** (perk) only applies once the prisoner has reported.
7. **Final whistle.** The freeze lifts at the end of the round, so reported prisoners share in the win or tie. The disqualified do not.

Travel time is a general estimate, not a live route (`HeuristicTravel` in `data/TravelTime.kt`): straight-line distance × 1.3 for detours, then the faster of walking at 5 km/h or transit at 18 km/h plus 15 min of waiting and walking. `WeatherFactor` scales it for conditions.

## Points and levels

Every verified event writes an `Award` to a ledger. Levels, perks and both leaderboards derive from it (`rules/Progression.kt`, `rules/Leaderboard.kt`).

| Event | Points |
|---|---|
| Jail an opponent | `10 × (1 + 0.2 × their level)`, once per target per round |
| Jailed | −5 |
| Capture the flag | 100 |
| Team wins (capture or enemy forfeit) | 25 each |
| Tie | 5 each |
| Leader whose flag survived a win or tie | 15 |
| Get home from an incursion unjailed | 2 per ping endured |
| Jailbreak | 20 per teammate freed |
| Disqualified | the round's net points, removed |

**Levels** are unlimited. Level *L* needs `50 × (L−1)^2.5` lifetime points, so every level costs more than the last.

**Advantages** arrive only at milestone levels, always multiples of 5, spreading further apart: 5, 15, 30, 50, 75, 105, 140, 180… Each milestone grants one step of *power*; milestones that are also multiples of 10 grant two. Nothing below level 5.

Each perk switches on at a power and grows with *k²*, where *k* is the steps since. Early advantages are barely noticeable; late ones decide games. Use-counts grow as ⌈*k*/2⌉. Caps only where uncapped would break the game.

| Power (level) | Perk | Effect | Per k² | Cap |
|---|---|---|---|---|
| 1 (5) | Ping delay | Second incursion ping later | 1 min | 60 min |
| 1 (5) | Proximity alert | Intruders within radius always ping you | 25 m | 5 km |
| 1 (5) | Tag window | Wider BLE window when tagging | 2 s | 5 min |
| 3 (30) | Identity delay | Roster identity withheld longer | ⌈k/2⌉ pings | — |
| 3 (30) | Keen Eye | Pings show intruder level | on | — |
| 4 (30) | Threshold | Grace on enemy ground before an incursion counts | 30 s | 15 min |
| 4 (30) | Counterintel | Decoys near you marked as decoys | 50 m | 5 km |
| 5 (50) | Decoy | Fake anonymous ping from enemy ground | ⌈k/2⌉ / game | — |
| 5 (50) | Sharp Lens | Tag photo distance tolerance | 3 m | +60 m |
| 5 (50) | Night Cover | Fewer ping recipients 1–5am local | divisor +0.1 | 1/10 |
| 6 (50) | Blur | Your pings off by up to a radius until identified | 10 m | 1 km |
| 6 (50) | Witness | Teammates near you share your alerts | 10 m | 1 km |
| 7 (75) | Standing | Captain draw weight | +0.05 | — |
| 7 (75) | Mentor | Nearby lower-level teammates earn a bonus share | 2% | 50% |
| 8 (105) | Shadow | Fewer ping recipients always | divisor +0.1 | 1/10 |
| 8 (105) | Crowd | Blur grows with local population density | ×0.05 | — |
| 9 (140) | Interrogate | Private extra ping on an intruder you were pinged about | ⌈k/2⌉ / game | — |
| 9 (140) | Bounty | Mark an enemy; your team's jail value on them multiplied | +0.1× | 3× |
| 10 (140) | Deliberate | Longer flag-placement window for your team | 1 min | 60 min |
| 10 (140) | Parole | Auto-release from jail | 48 h − 2 h | ≥ 2 h |
| 12 (180) | Vanish | Cancel your next scheduled ping | ⌈k/2⌉ / game | — |
| 12 (180) | Doppelgänger | Decoys walk on, one ping per 5 min | 1 + k² steps | 20 |
| 15 (330) | Bloodhound | Live position of intruders after a ping | 30 s | 10 min |
| 15 (330) | Tripwire | Alert when enemies cross in near your flag | 100 m | 3 km |
| 20 (525) | Flag Sense | Circle known to contain the enemy flag | shrinks from 3 km | ≥ 200 m |
| 20 (525) | Legacy | Your jail value freezes at 525, then discounts | 1% | 50% |
| 20 (525) | Last Stand | A tag against you is thrown out, publicly | ⌈k/2⌉ / game | — |

When hiding meets hunting, the higher level wins; ties go to the hunter. Blur yields to an equal-or-higher Bloodhound or interrogator; Counterintel works only on decoys from equal-or-lower senders; Tripwire beats Threshold for equal-or-higher defenders.

Level is snapshotted when teams are dealt and holds for the round.

**Rankings** are global (lifetime points) and per city (points earned there). Both show lifetime level. Ties share a rank.

## Structure

~~~
shared/   Compose Multiplatform (android + jvm). Rules, engine, chat, UI. No platform code.
  commonMain/.../geo        Distance, polygon, dividing line
  commonMain/.../rules      Partitioner, ping schedule, team assignment, verification, progression, leaderboard
  commonMain/.../engine     GameEngine: pure (state, input, time, random) → state + pings
  commonMain/.../data       GameBackend / PlatformServices interfaces, InMemoryBackend
  commonMain/.../ui         Screens
  commonTest                Rules tests (run: gradle :shared:jvmTest)
app/      Android: camera + EXIF, fused location foreground service, BLE proximity
~~~

The engine is meant to run on the server, authoritatively. Clients only render.

## Proximity

Each phone advertises a server-issued token that rotates every 15 minutes over BLE and records tokens it hears. Only the server can map a token to a player, so sniffing the air reveals nothing.

**Tile:** Tile (Life360) has no public developer API. The only existing clients are reverse-engineered and against its terms, and every player would need to own a Tile. Not used. BLE between phones covers the same ground without extra hardware.

## Open questions

- **Backend.** `InMemoryBackend` is single-device, for development only. Needs a real server (Firebase, Supabase, Ktor…) running `GameEngine`.
- **City data.** `CityDataSource` needs real feeds: census population, OSM buildings/land/water, barrier features. `DemoCityDirectory` is a synthetic New Orleans.
- **Weather.** `NoWeather` is a stand-in until a weather source is chosen.
- **Flag forfeit detection.** How a moved flag is detected (periodic re-photo, challenge by opponents, moderation) is not yet specified. `GameEngine.forfeit` is the hook.
- **Camera EXIF.** Many stock cameras strip GPS unless location tagging is turned on. An in-app CameraX capture would remove that dependency.
- **Background location** permission needs its own settings-screen request on Android 11+.

## Stack

AGP 9.4.1 · Kotlin 2.4.20 · Compose Multiplatform 1.12.1 · Material 3 · compileSdk 37 · minSdk 28 · Java 17+

## Workflows

Do **not** invent repository-local workflow implementations. Choose existing automation or describe a new generalized capability in `.github/workflow-request.yml`. New implementations belong in `HereLiesAz/workflows`.
