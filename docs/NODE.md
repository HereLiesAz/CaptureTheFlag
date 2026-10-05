# Running a node

A node is the game's server, held by whoever runs one: a Nostr relay, a media store for selfies, evidence and stream video, and, if it's drawn, a referee. How the nodes work together is in [DECENTRALIZED.md](DECENTRALIZED.md). This page is how to run one.

## What it needs

- A machine that stays on, with a public hostname. A small VPS is plenty: 1 CPU, 1–2 GB of memory, and disk for media (stream video adds up over a 4-day round).
- Ports 80 and 443 open. Phones only connect over `wss://`, so the node sits behind a TLS proxy.
- Docker with Compose.

## Start one

~~~
git clone https://github.com/HereLiesAz/CaptureTheFlag
cd CaptureTheFlag
DOMAIN=node.example.org docker compose -f deploy/compose.yaml up -d --build
~~~

Caddy fetches and renews the certificate for `DOMAIN`. Players enter `wss://node.example.org` in the app.

The first start creates the node's key in the `node-data` volume (`/data/node.key`). That key is the node's identity and reputation: eligibility to referee builds up on it over 30 days. Back it up; don't share it.

The node's public key is printed on startup:

~~~
docker compose -f deploy/compose.yaml logs node | grep "on ws://"
~~~

## Joining the network

A node alone is a panel of one: fine for trying the game, not for real play. To join others, set these in the environment (or in a `.env` file next to `compose.yaml`):

| Setting | What it does |
|---|---|
| `PEERS` | Other nodes' addresses, comma-separated (`wss://a.example,wss://b.example`). One is enough: the rest are found through announcements. |
| `REFEREES` | The shared roster: referee public keys, comma-separated. Every node should list the same ones. Nodes earn their way in after 30 days and 20 agreeing outcomes either way. |
| `DOMAIN` | This node's hostname. It becomes `PUBLIC_URL`, which the node announces so others can find it. |

A game needs five referees, so a real network needs at least five nodes, ideally run by different people.

## Optional

| Setting | What it does |
|---|---|
| `VOSK_MODEL` | A [Vosk model](https://alphacephei.com/vosk/models) directory, so this node can hear the challenge words in stream audio. Mount it into the container (see the commented line in `compose.yaml`) and point this at the mount, e.g. `/models/vosk`. `vosk-model-small-en-us-0.15` is enough. |
| `ARCHIVE` | A directory holding a clone of the network's private archive repository, or a synced Drive folder. Mount it in and point this at it. |
| `ARCHIVE_SYNC` | `external` if something else (Drive for desktop, rclone) keeps `ARCHIVE` in sync; otherwise the node pulls and pushes it with git. |
| `ATTESTATION_SIGNERS` | Hex SHA-256 digests of the app's signing certificates, comma-separated. Set it and only the genuine build's keys pass. |
| `ATTESTATION` | `off` accepts evidence from emulators and rooted phones. For development only; never on a node that referees real games. |
| `PORT`, `DATA_DIR` | Where the node listens and keeps its data inside the container. The defaults (`7447`, `/data`) suit the compose file. |

## Without Compose

The image runs on its own; put any TLS proxy in front of port 7447.

~~~
docker build -f node/Dockerfile -t ctf-node .
docker run -d --name ctf-node -p 127.0.0.1:7447:7447 -v ctf-data:/data \
  -e PUBLIC_URL=wss://node.example.org -e PEERS=wss://other.example ctf-node
~~~

Or without Docker: `./gradlew :node:installDist`, then run `node/build/install/node/bin/node` with the same settings. It needs Java 25 or newer, and `ffmpeg` on the path for speech.

## Updating

~~~
git pull
docker compose -f deploy/compose.yaml up -d --build
~~~

The node rebuilds every game it referees from its stored events on restart, so an update mid-round costs a few seconds, not the round. Keep restarts short anyway: a referee silent for 10 minutes is replaced.
