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

Navigation: two Compose destinations (`wishlist`, `settings`) in a `NavHost` in `MainActivity` (an `AppCompatActivity`, required for per-app locales).

### The AI core — constrained vs unconstrained generation

`WishRepository` is the heart of the learning project. It deliberately contrasts two prompting styles against the same DeepSeek `chat/completions` endpoint:

- `generateWishIdeas` — **constrained**: system prompt forces exactly 3 short items, `maxTokens=300`, `stop=["END"]`, plus client-side line parsing/cleanup. Demonstrates format + length + completion control.
- `generateUnconstrained` — **unconstrained**: bare user prompt, `maxTokens=2000`, no format/stop controls.

When changing generation behavior, preserve this contrast — it is the point of the project. Keep the explanatory comments above each method.

The generation prompt comes from a **string resource** (`R.string.prompt_generate_wish`), and the system prompt instructs the model to "respond in the same language as the user's message" — so AI output follows the app locale. Errors surface as `R.string.error_generate_idea` in `uiState.errorMessage`.

### Locale handling

Language is **not** stored in `SettingsRepository`. It uses AndroidX per-app locales: `AppCompatDelegate.setApplicationLocales(...)` in `SettingsScreen`, persisted automatically via the `autoStoreLocales` `AppLocalesMetadataHolderService` in the manifest and `@xml/locale_config`. `SettingsRepository` only persists the selected DeepSeek model (SharedPreferences).

### Data

Room (`Wish` entity, `WishDao`, `WishDatabase` v1, `exportSchema=false`). Wishlist is observed as a `Flow` collected into UI state.

## Conventions

- Package root `me.obrekht.wishu`; layered as `data/`, `network/`, `ui/`.
- All user-facing text in string resources (app is localized en/ru); never hardcode UI strings — they also drive AI language.
- Edge-to-edge is enabled (`enableEdgeToEdge()`); system bar icons follow the theme. See `feedback_keyboard_jump` memory before touching IME/keyboard insets on Samsung edge-to-edge.
