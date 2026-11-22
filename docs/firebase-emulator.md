# Firebase Local Emulator Suite (local dev)

This app authenticates with **Firebase Auth (phone OTP)**. For local development
you run the **Firebase Auth emulator** instead of the real project, so you can log
in with fake phone numbers, see no real SMS cost, and never touch
production/staging data.

The app connects to the emulator automatically when `ENV=dev` — see
`lib/config/firebase_initializer.dart` (`FirebaseAuth.instance.useAuthEmulator(host, port)`).
Project id: **`nhk-coop-staging`** (from [`firebase.json`](../firebase.json) /
`lib/firebase_options_dev.dart`).

> Only the **Auth** emulator is used by this app today. The steps below set it up.

## Contents

- [Prerequisites](#1-prerequisites)
- [Install the Firebase CLI](#2-install-the-firebase-cli)
- [Enable the Auth emulator in firebase.json](#3-enable-the-auth-emulator-in-firebasejson)
- [Start the emulator](#4-start-the-emulator)
- [Point the app at the emulator (env.json)](#5-point-the-app-at-the-emulator-envjson)
- [Run the app](#6-run-the-app)
- [Logging in with phone OTP](#7-logging-in-with-phone-otp)
- [The Emulator UI](#8-the-emulator-ui)
- [Troubleshooting](#troubleshooting)
- [Links](#links)

---

## 1. Prerequisites

- **Node.js** LTS (18+) and npm — <https://nodejs.org>
- **Java JDK 11+** (the Auth emulator runs on the JVM) — e.g.
  [Temurin](https://adoptium.net/). Check with `java -version`.
- A Google account with access to the Firebase project (only needed if you run
  the emulator against the real project id; see the offline tip in step 4).

## 2. Install the Firebase CLI

```bash
npm install -g firebase-tools
firebase --version          # verify
firebase login              # opens a browser to authenticate
```

(Alternative without npm: `curl -sL https://firebase.tools | bash`.)

## 3. Enable the Auth emulator in firebase.json

The repo's [`firebase.json`](../firebase.json) is the FlutterFire config and does
**not** yet declare emulator ports. Add an `emulators` block at the top level so
everyone uses the same ports (Auth on **9099**, UI on **4000**):

```jsonc
{
  "flutter": { /* …leave the existing block untouched… */ },
  "emulators": {
    "auth": { "port": 9099 },
    "ui": { "enabled": true, "port": 4000 },
    "singleProjectMode": true
  }
}
```

> You can also generate this with `firebase init emulators` and tick
> **Authentication Emulator** — it merges the block into `firebase.json`.

## 4. Start the emulator

From the repo root:

```bash
firebase emulators:start --only auth --project nhk-coop-staging
```

You should see something like:

```
✔  auth: Authentication Emulator running at http://127.0.0.1:9099
✔  All emulators ready! View status at http://127.0.0.1:4000
```

Leave this terminal running while you develop.

> **Fully offline tip:** use a throwaway `demo-` project id to run without any
> real Firebase project or login: `firebase emulators:start --only auth --project
> demo-nhk`. The Auth emulator is isolated, so the id only has to match between
> the CLI and (optionally) the app — functionality is identical.

## 5. Point the app at the emulator (env.json)

Edit `env/env.json` (copy from `env/env.dev.example.json` first). The host depends on
**where the app runs**, because "localhost" means the device, not your machine:

| Running on | `FIREBASE_BASE_URL_DEV` |
|---|---|
| **Android emulator** | `10.0.2.2` (alias for the host machine's localhost) |
| **iOS simulator** | `127.0.0.1` |
| **Physical device** | your computer's LAN IP (e.g. `192.168.1.20`) — device + computer on the same Wi-Fi |

```jsonc
{
  "BASE_URL": "https://api.example.com",
  "FIREBASE_BASE_URL_DEV": "10.0.2.2",   // Android emulator (see table)
  "FIREBASE_AUTH_PORT": 9099,
  "ENV": "dev"                            // "dev" is what turns the emulator on
}
```

`ENV=dev` sets `AppConfig.useEmulator = true`, so `FirebaseInitializer` calls
`useAuthEmulator(FIREBASE_BASE_URL_DEV, FIREBASE_AUTH_PORT)` at startup.

## 6. Run the app

With the emulator running (step 4):

```bash
flutter run --dart-define-from-file=env/env.json -d <device-id>
```

On start you're pointed at the local Auth emulator — no real SMS is sent.

## 7. Logging in with phone OTP

The Auth emulator never sends a real SMS. Two ways to get the code:

1. **Predefined test numbers (recommended for the team)** — in the
   [Emulator UI](#8-the-emulator-ui) → **Authentication** tab → add a phone number
   and a fixed 6-digit code. Entering that number in the app always accepts that
   code.
2. **One-off code from the logs** — trigger OTP in the app; the emulator prints the
   generated verification code in the terminal running `emulators:start` (and in
   the UI's logs).

Enter the code on the app's OTP screen as usual.

## 8. The Emulator UI

Open <http://127.0.0.1:4000> → **Authentication** to:

- see/add/delete emulated users,
- manage test phone numbers,
- clear all auth data between test runs.

Emulator state is **in-memory** by default — it resets when you stop
`emulators:start`. To persist across restarts:

```bash
firebase emulators:start --only auth --project nhk-coop-staging \
  --import=./.emulator-data --export-on-exit
```

(Add `.emulator-data/` to your local ignore if you don't want to commit it.)

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| logcat: `Firebase Auth Emulator unreachable at <host>:<port>` | The app logs this from `FirebaseInitializer._connectEmulator`. Emulator not running, wrong host, or the app launched without `--dart-define-from-file env/env.json`. |
| Connection refused on **Android emulator** | `FIREBASE_BASE_URL_DEV` must be `10.0.2.2`, not `127.0.0.1`. |
| Connection refused on a **physical device** | Use your computer's LAN IP, same Wi-Fi, and allow the port through the firewall. |
| `Could not start Auth Emulator ... Java` | Install JDK 11+ (`java -version`). |
| `Port 9099/4000 already in use` | Another emulator instance is running, or change the port in `firebase.json` **and** `FIREBASE_AUTH_PORT`. |
| OTP never accepted | Add a test phone number + fixed code in the Emulator UI (step 7), or read the code from the emulator terminal. |
| Real users/SMS appear | `ENV` isn't `dev`, so the app is hitting the real project — check `env/env.json` and that you passed `--dart-define-from-file`. |

## Links

- Emulator Suite overview — <https://firebase.google.com/docs/emulator-suite>
- Install & configure — <https://firebase.google.com/docs/emulator-suite/install_and_configure>
- Connect your app to the Auth emulator — <https://firebase.google.com/docs/emulator-suite/connect_auth>
- Firebase CLI reference — <https://firebase.google.com/docs/cli>
- FlutterFire (firebase_auth) — <https://firebase.flutter.dev/docs/auth/usage>
