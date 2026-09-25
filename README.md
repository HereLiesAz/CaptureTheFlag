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
| Win: an opponent photographs the flag within 40 m of its registered location | `Verification.flagCapture` |
| Jail: photo of an opponent standing in your territory. Photo EXIF must match the target's last fix (60 m), and the target's BLE token must have been heard within 60 s | `Verification.tag` |
| Incursion pings at entry, +60, +30, +15, +10, +5, then every 5 min. Each goes to a fresh random third (rounded up) of the opposing team. Identity attached from ping 6 | `rules/PingSchedule.kt` |
| Chat: city-wide (anyone registered, onlookers included), private team room, teammate-only DMs | `chat/Chat.kt` |

Every photo is also checked for freshness (2 min), and its EXIF location against the phone's live fused fix (60 m) to catch doctored metadata.

## Structure

~~~
shared/   Compose Multiplatform (android + jvm). Rules, engine, chat, UI. No platform code.
  commonMain/.../geo        Distance, polygon, dividing line
  commonMain/.../rules      Partitioner, ping schedule, team assignment, verification
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
- **Jailbreak.** The rescue mechanic is not yet specified. `GameEngine.release` is the hook.
- **Flag forfeit detection.** How a moved flag is detected (periodic re-photo, challenge by opponents, moderation) is not yet specified. `GameEngine.forfeit` is the hook.
- **Camera EXIF.** Many stock cameras strip GPS unless location tagging is turned on. An in-app CameraX capture would remove that dependency.
- **Background location** permission needs its own settings-screen request on Android 11+.

## Stack

AGP 9.4.1 · Kotlin 2.4.20 · Compose Multiplatform 1.12.1 · Material 3 · compileSdk 37 · minSdk 28 · Java 17+

## Workflows

Do **not** invent repository-local workflow implementations. Choose existing automation or describe a new generalized capability in `.github/workflow-request.yml`. New implementations belong in `HereLiesAz/workflows`.
