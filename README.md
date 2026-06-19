# Wishu

Android wishlist app with AI-powered idea generation via DeepSeek. Personal learning project exploring on-device AI agent patterns.

## Features

- Add, view, and delete wishlist items
- AI chat agent — streaming, multi-turn, with bullet ideas auto-convertible to wishlist items
- Four context-management strategies: Sliding Window, Summary, Sticky Facts, Branching
- Three-layer agent memory: long-term (persisted), facts (auto-extracted), short-term (session)
- Declared user profile: name, reply style, reply format, free-text constraints
- Per-turn token & cost accounting from DeepSeek's exact usage (main + memory helper calls)
- Two models: DeepSeek V4 Flash and V4 Pro
- Language setting (System default / English / Russian)

## Tech Stack

- **UI:** Jetpack Compose + Material 3 Expressive
- **Architecture:** ViewModel + StateFlow, single immutable `UiState` per screen
- **Database:** Room (wishlist + persisted chat history and long-term memory)
- **Network:** Retrofit 3 + OkHttp 5 + kotlinx.serialization, streaming via SSE
- **AI:** DeepSeek API (OpenAI-compatible)
- **Min SDK:** 26

## Setup

1. Get a DeepSeek API key from [platform.deepseek.com](https://platform.deepseek.com/)
2. Add to `local.properties`:
   ```
   DEEPSEEK_API_KEY=your_key_here
   ```
3. Build and run

## Project Structure

```
app/src/main/java/me/obrekht/wishu/
├── agent/          # Chat agent, context strategies, memory layers, user profile
├── data/           # Room entities, DAOs, database, repositories
├── network/        # DeepSeek API models and interface
├── ui/             # Compose screens and ViewModels
└── WishuApplication.kt
```
