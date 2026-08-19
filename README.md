# DSH Mobile

English | [中文](README.zh.md)

The Android client in the DeepSeek Harness family. Native Kotlin + Jetpack Compose, talking
straight to any **OpenAI-compatible** endpoint — no `dsh` engine on the phone.

`dsh-desktop` is a shell around a local engine. A phone can't run the Node engine, so this app
takes the other road: it implements streaming chat, reasoning display and local history itself, and
treats the gateway as its only dependency.

```
┌─ MainActivity (Compose) ──────────────────────────────┐
│  ChatScreen      drawer / transcript / composer        │
│  SettingsScreen  service, model, generation, UI, data  │
│  ui/markdown     in-house markdown subset + highlight  │
└───────────────┬───────────────────────────────────────┘
                │ StateFlow
┌───────────────▼───────────────────────────────────────┐
│  ChatViewModel   stream orchestration, services,       │
│                  stop / regenerate / edit-and-resend   │
│  data/ContextWindow  token-budget windowing, shared by  │
│                  the meter and the request builder so   │
│                  the number shown is the number sent    │
├───────────────────────────────────────────────────────┤
│  net/OpenAiClient   SSE, reasoning_content, errors     │
│  data/ConversationStore  one JSON per chat, coalesced  │
│  data/SettingsStore      settings.json                 │
└───────────────────────────────────────────────────────┘
                │ HTTPS
                ▼
   https://<gateway>/v1/chat/completions
```

## Features

**Conversation**
- Streaming replies with a stop button; turn streaming off for one-shot completions.
- **Reasoning traces.** DeepSeek's `reasoning_content` renders as its own collapsible card — open
  while the model thinks, folded once the answer starts — with elapsed time and length.
- **Markdown**: headings, lists (including task checkboxes), quotes, tables, rules, inline styles
  and links; code blocks carry a language label, one-tap copy and light syntax highlighting.
- Message actions: copy, regenerate, delete; user messages can be edited and re-sent.
- A reply cut short by `max_tokens` offers "继续生成"; one that produced only thinking explains why.
- Multiple conversations — searchable, renamable, pinnable, deletable, auto-titled. Export any
  conversation as Markdown.
- Each conversation can carry its own system prompt, falling back to the global one.

**Attachments**
- **Files**: anything that decodes as text — code, logs, Markdown, CSV, JSON, YAML — is inlined into
  the prompt as a `<file name="…">` block, so it **works on any text model**, no vision needed.
  Binary formats are refused outright rather than mangled.
- **PDF**: the platform can only rasterise PDFs, so pages become images (up to 8) for a vision model.
- **Images**: gallery or camera, downscaled to 1280px and re-encoded before upload.
- When the current model can't see, the button is **not disabled** — the app moves to a vision model
  in the same service and says so, refusing only when the service has none.
- Other apps can **share** text or images in; they land in the composer rather than being sent.
- Voice input through the system recogniser.

**Services and models**
- **Several OpenAI-compatible services** can be configured, each with its own base URL and key,
  switchable in one tap and testable in place. Every conversation remembers the service and model it
  used and restores that pair when reopened.
- The model list comes from `GET /v1/models`, grouped by service and badged with vision / reasoning /
  image-output / context length.

**Context**
- Trimmed against a **token budget**, not a message count. Automatic mode derives the budget from the
  model's own window, leaving room for the reply; manual mode picks from 4K…1M.
- The app bar shows live usage as `12K / 1M`, and says "裁剪 N" when older messages have to go.
- Attachments are charged at their real cost (measured text, flat rate per image).

**Also**
- **Self-update** — see the next section.
- System / light / dark theme; optional enter-to-send.
- Conversations and images stay in app-private storage; `allowBackup=false`.

## Self-update

No more sideloading by hand for every change. **设置 → 更新** (Settings → Updates) checks for a
newer build, downloads it in-app, verifies it, and hands it to the installer.

The default feed is this repository's own GitHub releases:

```
https://api.github.com/repos/adgry/dsh-mobile/releases/latest
```

Two shapes are understood — a GitHub `releases/latest` payload, or a self-hosted `update.json`:

```json
{
  "versionCode": 10301,
  "versionName": "1.3.1",
  "apkUrl": "https://example.com/dsh-mobile-1.3.1.apk",
  "sha256": "<lowercase hex, optional but strongly recommended>",
  "sizeBytes": 1780000,
  "notes": "Markdown release notes"
}
```

Point the setting somewhere else to host it yourself. When `sha256` is present the download is
verified before installing; without it you are trusting HTTPS alone.

Two things no app can work around:

- **The system must allow this app to install apps.** Android requires it; the app links straight to
  that toggle when it isn't granted, and shows the current state in settings.
- **Old and new builds must share one signing key**, or the install is refused. Back up `keystore/`.

## Publishing a release

GitHub Actions does it, because a release needs building, signing and asset upload together:

```sh
git tag v1.3.1 && git push origin v1.3.1
```

Or run the `Release APK` workflow manually and type the version. The run produces a release with the
APK attached, which the in-app updater then finds.

One-time setup under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | output of `base64 -i keystore/dsh-release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (`dsh` in this project) |
| `KEY_PASSWORD` | key password |
| `DEFAULT_API_KEY` | optional gateway key to pre-fill into the build |

Without `KEYSTORE_BASE64` the workflow fails on purpose rather than publishing an APK that cannot
install over an existing one.

## Install

Sideload `app/release/dsh-mobile-<version>.apk` (or
`app/build/outputs/apk/release/app-release.apk`). Requires Android 8.0 (API 26) or newer, and
"install unknown apps" enabled for whatever app you transfer it with.

It works on first launch: the development gateway (`https://token.sensenova.cn/v1`), a test key and
`deepseek-v4-flash` are pre-filled. Point it at your own service under **设置 → 服务**
(Settings → Service) and hit 测试连接 (Test connection).

## Building

Needs JDK 17+ (verified on 21) and an Android SDK with compileSdk 37 and build-tools 37.0.0.

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
cp secrets.properties.example secrets.properties   # optional: a gateway key to pre-fill

./gradlew assembleDebug
./gradlew assembleRelease       # R8-shrunk and signed
./gradlew :app:collectRelease   # also copies it to release/dsh-mobile-<version>.apk
```

The Gradle wrapper is committed and pinned to 9.7.0, so no local Gradle install is needed.

### The pre-filled API key

`defaultApiKey` in `secrets.properties` is injected at build time as `BuildConfig.DEFAULT_API_KEY`,
so a fresh install can talk to a gateway without typing a key. That file is untracked, which is why
this repository can be public without shipping a working credential; leave it empty for a build with
nothing pre-filled.

### Signing

`keystore.properties` points at `keystore/dsh-release.jks`. Neither is committed.

> **Back up `keystore/` and its password.** Self-update depends on signature continuity: lose the
> key and no future build can install over an existing one — users would have to uninstall first,
> losing their local conversations.

Without `keystore.properties`, release builds fall back to the debug key and still succeed — but
that APK is signed differently and will not install over a real one. Local experiments only.

## Notes worth keeping

**AGP 9 has built-in Kotlin.** Applying `org.jetbrains.kotlin.android` on top of it fails the build
outright. AGP 9.3.1 bundles KGP 2.2.10, so the `kotlin` version in `libs.versions.toml` must match
it, and the Compose and serialization compiler plugins follow that same version. Don't set
`jvmTarget` — it defaults to `android.compileOptions.targetCompatibility`.

**Compose BOM 2026.08.00 requires compileSdk 37.** Anything lower fails in
`checkDebugAarMetadata`, once per androidx dependency.

**Gateway streaming quirks** (`token.sensenova.cn`, DeepSeek dialect):
- Intermediate chunks carry `finish_reason: ""` rather than `null`; treat empty as absent.
- Token counts need `stream_options.include_usage: true`, and arrive in a trailing usage-only
  frame with `choices: []` — the parser must tolerate an empty `choices`.
- `code` inside an error object is sometimes a string, sometimes a number, so error messages are
  dug out of the raw JSON tree instead of a strict DTO.
- `supported_sampling_parameters` lists only `temperature` and `stop`; don't send `top_p`.
- There is a TPM limit that returns `429` (`inference tpm exhausted / 429001`). Those are marked
  retryable and get a retry button on the message.

**Pending images have to be reserved, not just counted.** Pictures sitting in the composer go out
with the next turn. Adding their cost to "used" without charging it against the budget lets the
meter read over budget while insisting nothing will be trimmed, and leaves the request no room for
them.

**An upgrade must not lose someone's key.** Builds before multi-service wrote `baseUrl`/`apiKey` at
the top level of the settings file. Those keys no longer exist on `AppSettings`, so
`ignoreUnknownKeys` would quietly discard them and fall back to the bundled defaults — the loader
therefore digs them out of the raw JSON and folds them into the first service. Covered by tests.

**`max_tokens` is a trap on reasoning models.** The budget is spent on `reasoning_tokens` first: a
probe with 200 burned all 200 on thinking and returned `finish_reason: length` with an empty body.
The field is therefore not sent unless you set it.

**A user pressing stop is not an error.** Stopping closes the socket and the blocking read then
throws `IOException("Socket closed")`, which can reach the collector ahead of the coroutine's
cancellation — painting a red error card over a perfectly good partial reply. The ViewModel records
the intent in a `stopRequested` flag instead of trying to recognise the exception.

**Streaming auto-follow can't be decided by "is the end visible".** A growing reply pushes the tail
below the fold between repaints, so any strict test flips false on the first chunk that overflows and
never recovers, freezing the transcript mid-answer. The list always ends in a spacer item, so the
threshold has to be "more than that spacer below the fold", with half a viewport of slack — enough to
keep up with streaming, loose enough to let the reader scroll back.

**Cleartext HTTP is permitted.** Every endpoint here is typed in by the user — a gateway, a
self-hosted model server on the LAN, an update manifest on a home box. Android's default would fail
those silently with an opaque error, which is worse than the risk the user already chose by entering
an `http://` URL. The tradeoff is real: over plain HTTP the API key travels unencrypted, so prefer
https for anything leaving the local network.

**A reply that produced only reasoning still needs its footer.** With a small `max_tokens` the budget
is spent on `reasoning_tokens` and the body comes back empty — exactly when the length warning and the
continue button matter most. Gating the footer on body text left a bare "已思考" card with no actions
at all.

**The token counter is an estimate, not the bill.** It estimates the text we send: a measured document
turn estimated 120 against a billed 182 — same order. But after a long reasoning pass this gateway
also counts the reasoning context as input (`↑4.3k` on that same turn), which is not the app sending
more. Trimming only needs the right magnitude, so the estimate deliberately errs high.

## Layout

```
app/src/main/java/com/dshmobile/app/
├── DshApp.kt            Application + hand-rolled container
├── MainActivity.kt      theme, chat/settings switch
├── data/                models, settings store, conversation store, context windowing
├── net/OpenAiClient.kt  OpenAI-compatible client (SSE + blocking + /models)
├── ui/                  ChatScreen, SettingsScreen, Composer, drawer, picker, messages
│   ├── components/      small buttons, typing dots, empty state, attachment thumbs
│   ├── markdown/        block parse, inline parse, render, code highlight
│   └── theme/           brand palette (no Material You, for cross-device consistency)
└── util/                image downscaling, clipboard, time and number formatting
```
