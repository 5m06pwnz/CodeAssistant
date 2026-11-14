# Code Assistant

Offline-first Android code editor with built-in `llama-server` (GGUF), Monaco editor, multi-tab buffers, sequential AI edit chains, and file-type-reinforced GBNF grammar.

## Features

- AMOLED Monaco IDE (offline, no CDN)
- Built-in `llama-server` as `libllama_server.so` (arm64-v8a)
- Zero-copy GGUF loading from SAF / Download
- Native file tools (list / find / grep / atomic write with mtime conflict detection)
- Sequential AI edit steps + review gate + syntax self-heal
- Per-file-type GBNF (raw language grammar for new files, ordered JSON edit envelope for existing files)

## Build

Push to GitHub and use the **Build Code Assistant APK** workflow (Actions → download `code-assistant-apk` artifact).

Local requirements: JDK 17, Android SDK 34, NDK 26.x, CMake, Ninja.

## Permissions

- All Files Access (MANAGE_EXTERNAL_STORAGE) required for workspace browsing and GGUF resolution on Android 11+.
- Model files should live under `Download/` for reliable path resolution.

## License

Provided as-is for evaluation. Add your preferred license before publishing.
