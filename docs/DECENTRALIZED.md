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
| **Referee** | A node drawn for a specific game: software, never a person | Holds that game's secrets, runs the engine on them, and signs what happened. Reviews disputed streams with automated checks. |
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
33000  action            player      join, co-captains, placeFlag, placeJail, tag, goLive, frame,
                                     endStream, dispute, appeal, decoy, vanish, interrogate, bounty
33001  position          player      location fix, NIP-44 to each referee
33002  evidence          player      photo hash + EXIF + pose + BLE sightings, NIP-44 to referees
34000  batch             referee     canonical order: event ids with sequence numbers
34001  outcome           referee     per-batch result: verdicts, awards, highlights, public notices
34002  ping              referee     one ping, NIP-44 to its recipients only
34003  commit            referee     hash commitments to secrets (flags, jails, BLE keys)
34004  reveal            referee     the secrets, after the round ends
34005  ruling            referee     one referee's vote on one review of a disputed stream (public)
34006  report            referee     that referee's full review, NIP-44 to each leader of both teams
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

1. **Order.** One referee leads each batch: it proposes the next list of pending events, and the others sign the same list if every event in it is real and unbatched. A batch is final when 3 of 5 have signed it. Each referee signs **one** batch per sequence number, ever, so two different batches can't both reach 3 unless somebody signs twice, and a double signature is public proof against its signer. If a batch goes unsigned for 15 seconds, the next referee on the panel leads.
2. **Replay.** Each referee feeds the final batch into `GameEngine`, with the secrets only referees hold, and gets the same transition.
3. **Attest.** Each signs an `outcome` with the transition's public parts: verdicts, awards, highlights, notices. Three matching outcomes make it official.
4. **Deliver secrets.** Pings, trail updates and interrogation results go out as `ping` events encrypted to their recipients only. Any referee may send them; recipients ignore duplicates.
5. **Tick.** The leader proposes a batch at least every minute, empty if need be, so timed rules (pings, parole, deadlines, the 4-day clock) advance even when nobody acts.

### Time

Every batch carries its leader's clock. The others refuse to sign a batch dated more than 30 seconds ahead of their own clocks, or not after the last batch. Evidence freshness (2 minutes) is judged against the batch time, not the phone's. A phone with a wrong clock produces stale evidence and fails; the referees don't.

A batch dated in the past is still signed. Its signers are already committed to it, and refusing it would stall the game. So a lying leader can hold the clock back for the batches it leads (1 in 5), but never push it forward.

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

## Streams and disputes

Captures and jailbreaks are live streams, so most of the time nothing needs judging: the defenders and the city watched it happen. Referees rule only when a defender disputes.

- **Live.** Within 1 km of the enemy flag, on enemy ground, the referees send the hunter (alone) a `go live` ping from their own position, never the flag's. A capture counts only from a stream live since then. That ping gives something away, which is why anyone on enemy ground is cut off from their team (below). A jailbreak goes live at least 50 m from the (public) jail. Everybody can watch. Every few seconds the phone sends a `frame`: its fix, and the SHA-256 of the video written since the last frame. The hashes, signed as they happen, pin the video: it can't be swapped afterward.
- **Challenge.** The batch that takes the streamer live draws two words, shown from the start and said with the winning frame, so the referees only ever need the 30 seconds after it. The randomness is seeded by the batch's contents and its leader's millisecond clock, so nobody, streamer included, knows or steers them early.
- **Winning frame.** A still with its sensor data, checked like any photo. The referees' footage ends 30 seconds later; the stream can go on as long as the phone lasts, a victory lap. Getting caught ends it on the spot. The stream itself can go on as long as the phone lasts: a victory lap, the player's own.
- **Dispute window.** 10 minutes, for the defending team. Undisputed, it counts.
- **Review.** Every referee on the panel runs `StreamJudge`, publishes a public vote, and seals its full report to every leader of both teams. The report lists every check: passed, failed, or not run and why. A majority of the panel decides.
- **Appeal.** A ruling takes effect after a 10-minute appeal window. Each team's leaders may appeal once per round; the second review is final. A review with no majority after 30 minutes lets the stream stand, so a stalled panel can't hold a game hostage.
- **Leaders aren't paid by rulings.** Captains earn a flat 300 and co-captains 100, win or lose, in place of any win or tie points. The people who see the reports first have no points riding on them.

The checks: an unbroken stream, live from before the approach, the challenge window, the winning frame (location, time, pose, facing, the leader's reference photo), the challenge heard in the audio (on a node with a speech model), and every segment matching the hash sent live (for segments on the reviewing node). The photo matcher runs where a node has one. Anything a node can't check is reported as not run, with why.

## Cut off

A jailed player, or anyone on enemy ground, can't talk to their team: no posting anywhere, no reading the team room or DMs. City chat stays readable. What they know gets out when they make it home, or when they say it on a stream everybody hears.

The app enforces this, but a modified app wouldn't, and NIP-17 messages are invisible to relays and referees by design. So team chat and DMs go through the referees instead: sealed to the panel, forwarded to teammates only while the sender isn't cut off, and held for a cut-off recipient until they're home. Referees see team chat; they already see positions. (Players can always phone each other. The game can't stop that, only make it not the game.)

## City onboarding

The first request for a city asks any node to survey it (`onboarding/`). The node publishes a `city.survey` with its result and a hash.

- Other nodes spot-check it: they recompute 10 random cells, allowing 10% tolerance for data that has drifted. If those match, they publish `city.survey.ack`.
- A survey with 3 acks is accepted. Its hash seeds the city's first referee draw.
- The survey is permanent. Re-surveys need a new survey plus 3 acks, and apply only to rounds that open afterward.

## Points, levels, careers

The ledger is the set of `outcome` events with awards, signed by a quorum. Highlights and "made a list" records are in the same events. Any node can rebuild leaderboards, careers, rivalries and the radio's memory from them. No node's database is authoritative; the signed outcomes are.

## Chat and radio

- **City chat** is plain public Nostr notes tagged with the city.
- **Team chat and DMs** go through the referees, sealed to the panel, so they can enforce the cut-off (see Cut off). Not built in the node yet.
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
| A referee signs two batches for the same sequence | Both signatures are public: proof. It loses eligibility. With 3 of 5, one double-signer and two honest referees who each saw a different batch could finalize both; 4 of 5 would close that, at the cost of stalling whenever two referees are down. |
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
3. **Referee panels.** Every node lists the same roster of referee keys (`REFEREES`). An open request draws a panel of 5 from it by the request's id. The panel runs the commit-reveal seed ceremony, then orders batches by rotating leader and 3-of-5 signatures, replays them through `GameEngine`, and each signs an `outcome`. Double signers are caught. A roster of one is a panel of one.
4. **Surveyor.** Serves `city.survey` using the existing `onboarding/` pipeline.

5. **Archive.** Mirrors to a git clone or a synced folder, bootstraps from it, and caches surveys there.
6. **Secrets.** NIP-44 v2 (`Nip44.kt`), checked against the official test vectors. Every in-game player event (actions, positions, BLE keys) is sealed to each referee on the panel, in one event; anything sent in the clear is ruled `unreadable`. Pings go out as one sealed event per recipient. Accepted flags and BLE keys get a public `commit` (`sha256(preimage|salt)`, the salt keyed by the sealed body, so every referee derives the same one and nobody else can) and a `reveal` when the round ends.

7. **Stream review.** Streams, disputes, votes counted by majority, sealed reports to every leader, one appeal per team.

8. **Views.** After every batch the proposer seals each player their view of the game (`GameView`: never the enemy flag, never anyone else's position) and publishes a public one for onlookers. Pings go per recipient, the intruder a handle until identified.
9. **Phone client.** `NodeBackend` (shared) plays the whole game over a node: sealed actions, verdicts from outcomes, views, pings, radio, chat. The app uses it once a `wss://` node address is set on the city screen; otherwise it runs the built-in single-phone test server. The player's key is made on the phone and kept under an Android Keystore key.

10. **Media store.** `PUT`/`GET /media/<sha256>` on every node, Blossom-style: a file is named by its hash, uploads carry a signed kind-24242 authorization. Selfies and stream video are public; evidence photos are AES-GCM encrypted on the phone, the key riding only inside the sealed action. Streams are recorded in 5-second segments, each a playable video whose hash is the frame's `chunk`, uploaded as it's made; viewers play them back to back as they arrive. Victory-lap segments are announced as kind-35001 events. Referees check the segments on their node against the hashes sent live.

11. **Hearing the challenge.** A node with a Vosk model (`VOSK_MODEL`) and ffmpeg listens to the segments after the winning frame for the two challenge words: offline, so the audio never leaves the node. It listens only for those words, not a transcript. Without a model, the check is reported as not run.

What the prototype cuts, and must not ship with: speech recognition is English-only and depends on the node having a model; media lives only on the node it was uploaded to (no replication), so a referee on another node reports the video check as not run; the roster is a fixed list rather than an eligibility rule, and the panel is drawn by the open request's id rather than the city's last seed. A referee that withholds its seed reveal stalls the round, and one that goes silent isn't replaced. Levels come from the awards each referee has itself seen, so referees on different past games could price a capture differently. Evidence isn't attested. Highlights aren't published, so careers and rivalries are thin over the network, and team chat goes teammate to teammate rather than through the referees.

Next: media replication between nodes, key attestation, the matcher, eligibility and replacement, levels frozen at round open from quorum outcomes, highlights, and team chat through the referees.
