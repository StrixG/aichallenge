# Wishu

Android wishlist app with AI-powered idea generation via DeepSeek.

## Features

- Add, view, and delete wishlist items
- Generate wish ideas with DeepSeek AI (responds in user's language)
- Persistent local storage with Room
- Language setting (System default / English / Russian)

## Tech Stack

- **UI:** Jetpack Compose + Material 3
- **Architecture:** ViewModel + StateFlow
- **Database:** Room
- **Network:** Retrofit + kotlinx.serialization
- **AI:** DeepSeek API (`deepseek-v4-flash`)
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
├── data/           # Room entities, DAO, database, repository
├── network/        # DeepSeek API client and models
├── ui/             # Compose screens and ViewModel
└── WishuApplication.kt
```
