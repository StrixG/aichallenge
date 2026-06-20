# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

Wishu is an **AI learning project**, not a production app — favor simple, direct code over abstraction layers. Single-module Android app: a wishlist with DeepSeek-powered idea generation.

## Build & run

```powershell
.\gradlew assembleDebug        # build debug APK
.\gradlew installDebug         # build + install on connected device/emulator
.\gradlew lint                 # Android lint
.\gradlew build                # full build (no unit/instrumented tests exist yet)
```

Requires a DeepSeek API key in `local.properties`:

```
DEEPSEEK_API_KEY=your_key_here
```

The key is read at configure time and injected per-variant as `BuildConfig.DEEPSEEK_API_KEY` via the `androidComponents.onVariants` block in `app/build.gradle.kts` — not through `buildConfigField` in `defaultConfig`. Missing key falls back to empty string (build still succeeds; API calls fail at runtime).

Targets bleeding-edge SDKs (`compileSdk`/`targetSdk` = 37) and alpha Material 3 Expressive (`material3:1.5.0-alpha21`) — expect APIs marked `@ExperimentalMaterial3Api` / `@ExperimentalMaterial3ExpressiveApi`.

## Architecture

Manual DI, no Hilt/Koin. `WishuApplication` owns lazy singletons: `database`, `settingsRepository`, `deepSeekApi` (Retrofit + kotlinx.serialization + OkHttp). ViewModels are `AndroidViewModel`s that reach into the app to build their own repositories — e.g. `WishlistViewModel` constructs `WishRepository(app.database.wishDao(), app.deepSeekApi)`. UI state is a single immutable `*UiState` data class exposed via `StateFlow`, mutated with `_uiState.update { it.copy(...) }`.

Navigation: three Compose destinations (`wishlist`, `chat`, `settings`) in a `NavHost` in `MainActivity` (an `AppCompatActivity`, required for per-app locales).

### The AI core — the chat agent

`agent/WishChatAgent` is the single generation path against the DeepSeek `chat/completions` endpoint. It is a self-contained **agent**: it owns the active transcript and all request/response logic — callers see `send` / `regenerate` / `restore` / `loadHistory` / `reset`, never a `ChatRequest`. The system prompt (`CHAT_SYSTEM_PROMPT`) asks for concise wishlist/gift brainstorming and "reply in the same language as the user's most recent message" — so output follows whatever the user types.

Responses **stream** token-by-token (SSE): `stream=true` + `stream_options.include_usage`, parsed line-by-line (`data: {…}` … `data: [DONE]`) via OkHttp. `send`/`regenerate` return `Flow<ChatEvent>` — `Token(delta)` per chunk, `MemoryUpdating` before the post-turn helper calls, then one `Complete(tokens, aux)`. Because the debug `httpClient` attaches a BODY-level logging interceptor that buffers the whole body, the agent uses a separate `WishuApplication.streamingHttpClient` (same auth/retry/timeouts, no body logging). `ChatViewModel` collects tokens into an `assistant` `ChatUiMessage`, then `parseWishItems(content)` turns bullet lines into per-item "add to wishlist" buttons (write via `WishRepository.addWish`). Errors surface as `R.string.error_chat`; a 400 mentioning "maximum context length" maps to a distinct `ContextWindowExceededException`.

The turn is committed to `history` only **after** a successful reply, so a rejected request leaves no dangling turn.

**Three memory layers** (`MemorySnapshot`, rendered in the chat's memory panel), all owned by the agent and orthogonal to the context strategy:
- **Short-term** — the raw transcript (`history`); in-memory, session-scoped.
- **Working** — task-specific key-value `facts` (recipient, budget, likes…); refreshed every turn, cleared on `reset()`.
- **Long-term** — durable user profile; refreshed every turn, **survives `reset()`** (it's user data, not session data), persisted in Room.

Working + long-term are **always-on**: injected as system messages on every request and refreshed every turn via a cheap non-streaming `SUMMARY_MODEL` (`deepseek-v4-flash`) helper call. Working memory and the long-term profile are serialized as **TOON** (`agent/Toon.kt`) — flat `key: value` lines, no braces/quotes/commas, fewer tokens than JSON for the helper prompts; the DeepSeek wire format stays JSON. The long-term prompt is carefully scoped to record only facts about *the user*, never the gift recipient.

**Context strategy** (`ContextStrategy`, separate axis — only decides how the raw transcript is trimmed; agent branches in a single `when`, no class hierarchy), default `SLIDING_WINDOW`:
- `SLIDING_WINDOW` — last N messages, no extra call.
- `SUMMARY` — running prose summary of folded-away older turns (folded via a flash call once the un-folded tail outgrows the threshold) + the recent raw tail.
- `STICKY_FACTS` — only the last 2 messages, leaning on the always-on working-memory layer.
- `BRANCHING` — the active branch's full transcript; context is managed by *which* branch you're on. Branches form a tree (`chat_branches`): a child truncates its parent's transcript at `forkAtCount`, then appends its own messages. `ChatScreen` exposes earlier forks as a version pager.

`UserProfile` (`agent/UserProfile.kt`) is the user's **declared** preferences (name, `ReplyStyle`, `ReplyFormat`, free-text constraints) set in Settings — distinct from the *inferred* long-term memory. Passed per-call (agent stays stateless about it) and injected above the learned profile so stated preferences win; an empty profile costs no tokens.

`TokenLedger` (`agent/TokenAccounting.kt`) records exact per-turn usage from DeepSeek's `usage` chunk (main call + the headline `aux` helper call — the SUMMARY fold or the working refresh); resets each session.

`ChatHistoryRepository` persists transcript (per branch), summary, sticky facts, branch tree, and long-term memory so context survives an app restart and branch switches; `restore`/`loadHistory` re-seed the agent from it. The chat is reached from the wishlist top-bar AutoAwesome button (`onOpenChat` → `navigate("chat")`).

### Locale handling

Language is **not** stored in `SettingsRepository`. It uses AndroidX per-app locales: `AppCompatDelegate.setApplicationLocales(...)` in `SettingsScreen`, persisted automatically via the `autoStoreLocales` `AppLocalesMetadataHolderService` in the manifest and `@xml/locale_config`. `SettingsRepository` (SharedPreferences) persists the selected DeepSeek model, the `ContextStrategy`, the active branch id, and the declared `UserProfile` (the task state machine is persisted in Room, not here). It also exposes a non-persisted `longTermClearedAt` signal so a live chat session drops its in-memory long-term copy when memory is wiped from Settings (otherwise the still-loaded agent re-persists it next turn).

### Data

Room (`WishDatabase`, currently **v7**, `exportSchema=false`) with explicit migrations 1→7 — never destructive (except the v7 task_state recreate, which only throws away session-scoped state), each adds a table:
- v1 `wishes` (`Wish`/`WishDao`) — wishlist, observed as a `Flow` into UI state.
- v2 `chat_messages` — persisted transcript (gains a `branchId` in v4).
- v3 `chat_summary` — single-row running summary for the SUMMARY strategy.
- v4 `chat_branches` (root branch seeded) + `chat_facts` — branch tree + sticky working-memory facts.
- v5 `long_term_memory` — persistent user profile (untouched by session clear).
- v6 `task_state` — single-row task state machine snapshot (session-scoped, wiped by session clear). v7 dropped its `awaitingApproval` column. The FSM (`agent/TaskState.kt`) advances one `stage.next` per turn via the single `advance()` mutator, code-gated so the model can never skip. The transition runs **pre-turn** — `WishChatAgent.advanceTaskState(userMessage)` (helper flash call) decides completion *before* the reply streams, so the reply is generated under the new stage and the UI stage badge flips immediately (`ChatEvent.TaskAdvanced`); content never lags the stage by a turn. **PLANNING completion is deterministic**: the helper reports the four requirement slots (`recipient_known`/`occasion_known`/`budget_known`/`tastes_known`) and *code* requires all four — the model's single `stage_complete` boolean proved flaky there. Later stages still use the model's `stage_complete`. (An earlier `confidence`/`awaitingApproval` human-approval banner was removed entirely in v7 — no gate, every completion auto-advances.)

## Conventions

- Package root `me.obrekht.wishu`; layered as `data/`, `network/`, `agent/`, `ui/`.
- All user-facing text in string resources (app is localized en/ru); never hardcode UI strings — they also drive AI language.
- Edge-to-edge is enabled (`enableEdgeToEdge()`); system bar icons follow the theme. See `feedback_keyboard_jump` memory before touching IME/keyboard insets on Samsung edge-to-edge.
