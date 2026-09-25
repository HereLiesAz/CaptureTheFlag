# Decentralized server

No company runs this game. Phones play it; volunteer **nodes** keep it honest. This document is the design; `node/` is the first node.

## Goals

- **No single owner.** Anyone can run a node. The game survives any node, including the first one, disappearing.
- **Same rules everywhere.** Every honest participant computes the same game state from the same inputs.
- **Secrets stay secret.** Positions, flag locations, ping recipients and BLE token owners are never public during a round.
- **Cheating is expensive.** Not impossible: a decentralized game can't promise that. It should take collusion, not a modified app.

## Non-goals

- Anonymity from referees. Referees see positions; that is their job.
- Running on phones alone. Phones sleep, lose signal and die. Nodes are always-on.

## The pieces

| Piece | Who | What it does |
|---|---|---|
| **Player** | A phone | Signs actions. Encrypts its position and evidence to the game's referees. Decrypts what's addressed to it. |
| **Node** | Anyone with a machine that stays on | Relays and stores events, stores media, and, if eligible, referees games. Runs city onboarding surveys and the photo matcher. |
| **Referee** | A node drawn for a specific game | Holds that game's secrets, runs the engine on them, and signs what happened. |
| **Onlooker** | A registered user not in the game | Reads the public feed: radio, city chat, results. Can't see secrets. |

## Why the engine already fits

`GameEngine` is a pure function: `(state, input, time, randomness) → state + pings + awards + notices + highlights`. It touches no clock, no network and no global random. Give two machines the same ordered inputs and the same seed, and they produce the same game. Everything below exists to agree on those inputs and that seed, and to keep some inputs secret from the wrong people.

## Transport: Nostr

Nostr is a network of simple relays that store and forward signed JSON events over WebSockets. It has no accounts or servers to trust, and many relays already exist.

- **Identity** is a Nostr keypair (secp256k1, Schnorr signatures), generated on the phone and never leaving it. The public key is the player id.
- **Events** are standard Nostr events. The game uses its own kinds (below) and tags them with the city and game.
- **Encryption** of secret payloads uses NIP-44 (versioned, authenticated). A payload for several people is encrypted once per recipient.
- A node is a Nostr relay that also understands the game. Players can use any relay; game nodes are just the ones that referee.

### Event kinds

~~~
kind   name              signed by   content
31000  city.survey       node        onboarding result: boundary, grid, cells (public)
31001  city.survey.ack   node        "I recomputed a sample and agree" (public)
32000  game.open         referees    new round: city, deadlines, referee set, seed commitments
32001  seed.reveal       referee     its seed share, after all commitments are in
33000  action            player      join, co-captains, placeFlag, placeJail, tag, capture,
                                     jailbreak, decoy, vanish, interrogate, bounty
33001  position          player      location fix, NIP-44 to each referee
33002  evidence          player      photo hash + EXIF + pose + BLE sightings, NIP-44 to referees
34000  batch             referee     canonical order: event ids with sequence numbers
34001  outcome           referee     per-batch result: verdicts, awards, highlights, public notices
34002  ping              referee     one ping, NIP-44 to its recipients only
34003  commit            referee     hash commitments to secrets (flags, jails, BLE keys)
34004  reveal            referee     the secrets, after the round ends
35000  radio             referee     a commentary line (public)
~~~

Player actions carry no authority. Nothing happens until a referee quorum signs a `batch` that includes the action and an `outcome` that says what it did.

## Referees

### Who

Nodes opt in to referee. A node is eligible for a city if it has:

- a key at least 30 days old,
- published agreeing `city.survey.ack` or `outcome` events that later matched the quorum at least 20 times,
- no player in the game on the same key, and no player who has shared an IP with it in the last 30 days (reported by other nodes, best effort).

### How many, and how chosen

Each game gets **5 referees; 3 must agree** (a quorum). They are drawn from the eligible pool by the previous round's final seed for that city. The first round in a city uses the survey's hash. Nobody picks their own referees.

### What they do

1. **Order.** Every few seconds, each referee proposes the next batch of pending events. A batch is final when 3 of 5 have signed the same list. Ties break by lowest event id.
2. **Replay.** Each referee feeds the final batch into `GameEngine`, with the secrets only referees hold, and gets the same transition.
3. **Attest.** Each signs an `outcome` with the transition's public parts: verdicts, awards, highlights, notices. Three matching outcomes make it official.
4. **Deliver secrets.** Pings, trail updates and interrogation results go out as `ping` events encrypted to their recipients only. Any referee may send them; recipients ignore duplicates.
5. **Tick.** Referees also submit a signed time tick every minute, so timed rules (pings, parole, deadlines, the 7-day clock) advance even when nobody acts. The engine's `now` is the median of the quorum's clocks for that batch.

### Time

Every batch carries the median of its signers' clocks. Evidence freshness (2 minutes) is judged against the batch time, not the phone's. A phone with a wrong clock produces stale evidence and fails; the referees don't.

## Randomness

The engine needs randomness for the team deal, captains, ping recipients, blur offsets and the city split.

1. At `game.open`, each referee publishes `H(share)`.
2. Once all 5 commitments are in, each publishes its `share`.
3. `seed = H(share₁ ‖ … ‖ share₅)`.
4. The engine's `Random` is seeded per batch with `H(seed ‖ batch sequence)`.

A referee that withholds its reveal is replaced (see Failure). Its share is dropped and the others' shares still decide. No single referee can steer the draw, because it must commit before seeing the others.

## Secrets

| Secret | Who knows during the round | How | Public after |
|---|---|---|---|
| Player positions | Referees | `position` events NIP-44 to each referee | Never; only derived pings |
| Ping contents | The recipients | `ping` NIP-44 per recipient | Never |
| Flag location and photo | Own team, referees | `action` placeFlag NIP-44 to team and referees; `commit` publishes `H(lat, lng, photoHash, salt)` | `reveal` at round end, checked against the commitment |
| Jail location | Everyone | Public by the rules | Already public |
| BLE token owners | Referees | Each player derives tokens `HMAC(k, window)`; `k` goes NIP-44 to referees; `commit` publishes `H(k)` | `k` revealed after the round |
| Seed shares | Their referee | commit-reveal | After reveal |

The flag commitment is what lets anyone check after the fact that a flag never moved and that a capture was really at it. The referees ruled during the round; the reveal makes their ruling auditable.

## Evidence

Photos are too big for events. Media is stored by nodes, content-addressed by SHA-256 (the Blossom convention on Nostr).

- The phone uploads the photo, encrypted to the referees, to any node it likes. The `evidence` event carries the hash, EXIF, pose, BLE sightings and a **key attestation**.
- **Key attestation**, not Play Integrity. Android can prove, with a certificate chain rooted in Google's hardware attestation root, that a signing key lives in secure hardware on a genuine device running an unmodified boot. The app signs each evidence event with such a key. Referees verify the chain **offline**. Play Integrity would need every referee to call Google's servers with its own project; key attestation needs no one.
- Referees run the same `Verification` checks the engine already has: GPS, time, pose, facing, the leader's reference photo. A node that runs the **photo matcher** adds a visual similarity score to its `outcome`. When at least 3 referees report a score, the median is used; otherwise the check is skipped, as it is today.

## City onboarding

The first request for a city asks any node to survey it (`onboarding/`). The node publishes a `city.survey` with its result and a hash.

- Other nodes spot-check it: they recompute 10 random cells, allowing 10% tolerance for data that has drifted. If those match, they publish `city.survey.ack`.
- A survey with 3 acks is accepted. Its hash seeds the city's first referee draw.
- The survey is permanent. Re-surveys need a new survey plus 3 acks, and apply only to rounds that open afterward.

## Points, levels, careers

The ledger is the set of `outcome` events with awards, signed by a quorum. Highlights and "made a list" records are in the same events. Any node can rebuild leaderboards, careers, rivalries and the radio's memory from them. No node's database is authoritative; the signed outcomes are.

## Chat and radio

- **City chat** is plain public Nostr notes tagged with the city.
- **Team chat and DMs** are NIP-17 private messages: sealed, gift-wrapped, invisible to relays and referees alike.
- **Radio** lines are generated by the referee that finalizes a batch and published as `radio` events. The booth rules stay as they are. It never leaks what the referees know: the `Commentator` only reads what the public outcome contains, plus positions for speculation, and its speculation rules already say intent, not position.

## Archive

A shared folder is the network's memory: a **private GitHub repository** or a **gated Google Drive folder**. Nodes with access mirror into it:

~~~
events/<game>.jsonl     every signed event, one per line
cities/<city>.json      accepted city surveys
nodes/<pubkey>          how to reach each node
~~~

- **Bootstrap.** A new node loads the archive before joining, instead of asking every relay for history.
- **Surveys once.** A node checks `cities/` before surveying; a city is surveyed once across the whole network.
- **Not trusted.** Every event is re-verified by signature on load; tampered lines are dropped. Access to the folder lets you add noise, not change history. If the archive disappears, the network loses convenience, not truth.
- **Sync.** A git archive is pulled, committed and pushed by the node. A Drive folder is kept in sync by Drive for desktop or `rclone mount`; the node only reads and writes files. Credentials stay with git or the sync client.
- **Gating** is the folder's own sharing: invite the people who run nodes.

GitHub and Google are single companies, which is why the archive is a mirror and never the source of truth.

## Failure

| What goes wrong | What happens |
|---|---|
| A referee goes silent for 10 minutes | The other 4 continue (3 is still a quorum). A replacement is drawn by the current seed and receives the secrets from the remaining referees, re-encrypted to it. |
| Two referees silent | Replacements drawn as above. Play pauses (no batches) until 3 are live. The clock pauses too: deadlines extend by the pause. |
| Referees disagree on an outcome | The minority's outcome is ignored. Repeated disagreement costs eligibility. The disagreement is public, so anyone can replay and see who was wrong. |
| A player's phone is offline | Its actions queue locally and are submitted on reconnect. Evidence freshness (2 min) still applies, so late evidence fails, as it should. |
| A relay censors a player | Players publish to several relays; referees read from several. |
| Network partition | Whichever side holds a quorum of referees keeps playing; the other side's events wait. |

## Where it's weak

- **Collusion.** 3 referees working with one team can see its opponents' positions and rule unfairly. Countermeasures: random draw, eligibility rules, public disagreement, the post-round reveal (so collusion is detectable after the fact), and reputation loss. It is detectable, not preventable.
- **Sybil nodes.** Someone can run many nodes to get drawn more often. The age and track-record requirements slow this; they don't stop a patient attacker.
- **Referees see positions.** Unavoidable if anyone is to rule on incursions without trusting phones.
- **Rooted phones** fail key attestation and can't produce valid evidence. That's intended, and it will annoy some players.

## What your node does

Your node is one of the always-on machines:

1. **Relay.** Stores and forwards game events. Any Nostr client can use it.
2. **Media store.** Holds encrypted evidence by hash.
3. **Surveyor.** Onboards cities when asked, and acks others' surveys.
4. **Referee.** When drawn, holds a game's secrets and runs the engine.
5. **Matcher**, optional. Scores capture photos against registration photos.

## Prototype

The first node (`node/`) proves the core loop, not the whole design. Built so far:

1. **Relay.** A NIP-01 relay: WebSocket, `EVENT`/`REQ`/`CLOSE`, Schnorr signature checks, filters, persistence to disk.
2. **Game kinds.** Accepts and indexes the kinds above.
3. **Referee.** A single-referee mode (quorum of 1) that orders game `action` events, replays them through `GameEngine` with a commit-reveal seed, and publishes `outcome` and `radio` events. Multi-referee quorum comes next, once one referee is solid.
4. **Surveyor.** Serves `city.survey` using the existing `onboarding/` pipeline.

5. **Archive.** Mirrors to a git clone or a synced folder, bootstraps from it, and caches surveys there.

What the prototype cuts, and must not ship with: positions, pings, flag registrations and BLE keys travel **in plaintext** (the design says NIP-44), one referee rules alone (the design says 3 of 5), and evidence isn't attested.

Next: NIP-44 secrets, quorum ordering and the seed ceremony across 5 referees, key attestation, the media store, the matcher, replacement and pause, and a phone client speaking this protocol instead of `InMemoryBackend`.
