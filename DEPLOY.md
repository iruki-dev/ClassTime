# Deploying ClassTime to Firebase App Distribution

One-time setup, then `./deploy.sh` builds, signs, bumps the version, and uploads.

## One-time, in this container

```bash
./scripts/setup-build-env.sh     # JDK 17 + Android SDK + licences + Firebase CLI
./scripts/create-keystore.sh     # the fixed signing key (run once, ever)
```

`setup-build-env.sh` installs a JDK 17 because the devcontainer's default JDK 25
is too new for this project's Gradle 8.9 / AGP 8.5.2, which fail outright on it.

## One-time, authentication

Two routes. For a personal project, use the first.

### A. Firebase CLI login (recommended, no service account)

```bash
firebase login --no-localhost     # prints a URL; paste the code back
firebase projects:list            # find your project id
firebase apps:list ANDROID        # prints the App ID
```

`apps:list` and `apps:create` take a **platform** (`ANDROID`/`IOS`/`WEB`), not a
project id, and they need an active project. `firebase use` refuses to run here
because this is an Android repo with no `firebase.json`, so the project is
pinned with a two-line `.firebaserc` instead (committed; a project id is not a
secret). Without it, pass `--project <project-id>` on every command.

The Gradle plugin reads the token the CLI stores at
`${XDG_CONFIG_HOME:-~/.config}/configstore/firebase-tools.json`, so nothing
further is needed. No Google Cloud console, no IAM role, no JSON key.

If the project has no Android app yet:

```bash
firebase apps:create ANDROID ClassTime -a dev.iruki.classtime
```

### B. Service account JSON (for CI)

Google Cloud console > IAM > Service Accounts > create one, grant it
**Firebase App Distribution Admin**, create a JSON key, then:

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
```

Use this when a build machine cannot run an interactive login. Some
organizations block downloadable service account keys by policy; route A does
not need one.

### Either way

```bash
export FIREBASE_APP_ID=1:000000000000:android:0000000000000000000000
export FIREBASE_TESTERS=you@example.com
```

Put these in your shell profile so they persist. Anything in `gradle.properties`
works too (`firebaseAppId`, `firebaseTesters`, `firebaseGroups`), but prefer the
environment for values you would rather not commit.

Check what is resolved at any time:

```bash
./gradlew deployConfigCheck
```

## Deploy

```bash
./deploy.sh                     # bump versionCode, build release, upload
./deploy.sh --variant debug     # debug build type instead
./deploy.sh --no-bump           # reuse the current versionCode
./deploy.sh --skip-upload       # build and sign only
./deploy.sh --notes "fixed the timetable bug"
```

## How the pieces fit

| Concern | Where |
| --- | --- |
| Plugin + upload tasks | `app/build.gradle.kts`, `appDistributionUpload{Debug,Release}` |
| Signing key location | `keystore.properties` (gitignored) or `CLASSTIME_KEYSTORE_*` env vars |
| Version counter | `version.properties`, bumped by `./gradlew bumpVersionCode` |
| Config precedence | env var > `local.properties` / `keystore.properties` > Gradle property |
| Auth precedence | service account JSON > `FIREBASE_TOKEN` > Firebase CLI login |

### Why one fixed key for debug *and* release

Android only installs an update in place when the new APK is signed with the
same key as the installed one. AGP normally signs debug builds with a
per-machine auto-generated key and leaves release builds unsigned, so every
build could differ. Both build types now use the single keystore from
`keystore.properties`, which is what makes "update" work in App Tester instead
of forcing an uninstall.

Verify any APK's signature:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The `SHA-256 digest` must be identical across builds.

### Why versionCode auto-increments

Android refuses an update whose `versionCode` is not greater than the installed
one. `./deploy.sh` runs `bumpVersionCode` before building, as a separate Gradle
invocation, because the build reads `version.properties` at configuration time.

## Never commit

`secrets/`, `keystore.properties`, `local.properties`, `*.jks`, and any service
account JSON are all in `.gitignore`. **Back up the keystore and its password**
(password manager): losing them means no future build can update an installed
copy of the app without uninstalling it first.
