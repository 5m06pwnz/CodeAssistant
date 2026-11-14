# Code Assistant Audit Report

**Date:** 2026-11-14  
**Source:** Provided specification combining Kojot + Szop patterns for offline Monaco + built-in llama-server Android app.

## Summary

The specification is a solid, ambitious, production-oriented design for a local-first mobile code editor with AI editing powered by on-device GGUF models. It correctly solves several hard Android + WebView + llama.cpp problems (Monaco workers under virtual HTTPS origin, zero-copy SAF GGUF resolution, process supervision, GBNF grammar for structured edits).

However, several issues prevent a clean first build and introduce reliability / security / maintainability risks.

## Critical Issues (will break build or runtime)

1. **Missing Gradle wrapper & dependencies**
   - No `gradlew`, no `gradle/wrapper/`. CI uses `gradle assembleDebug` which relies on the Gradle setup action, but local builds will fail.
   - `app/build.gradle.kts` declares plugins but has **zero dependencies** (no Kotlin stdlib explicit, no AndroidX, though pure Activity + WebView may work). Still fragile.

2. **MainActivity.kt typos / incomplete paste artifacts**
   - Fixed during extraction: `model reconPath` → `modelRealPath`.
   - `onBackPressed` and `onActivityResult` are deprecated; modern replacement (`OnBackPressedDispatcher` / Activity Result API) is preferred for API 33+.

3. **CI llama.cpp build is fragile**
   - Hard-coded tag `b12387`. May not exist or may have moved.
   - Requires Ninja (`-G Ninja`) but `sdkmanager` / setup does not install Ninja. Need `apt-get install ninja-build` or use Unix Makefiles.
   - `cmake --build ... --target llama-server` assumes the binary lands in `build-llama/bin/`. Layout can change across llama.cpp versions.
   - No `apt-get` step for `cmake`, `ninja-build`, `git`, etc. (Ubuntu image has some, but not guaranteed complete).

4. **Missing `proguard` / R8 / minify configuration**
   - Debug build has `isDebuggable = false` (odd for debug) and no proguard rules. JS bridge methods can be stripped in release.

5. **Asset packaging**
   - CI correctly copies Monaco + index.html into assets, but the repo itself should either commit a stub or document that assets are build-time only.

## High-Severity Issues

6. **Security surface**
   - `MANAGE_EXTERNAL_STORAGE` + full workspace root access is powerful. The `safe()` path canonicalization is good, but still grants broad FS access once permission is given.
   - Random per-launch `--api-key` is excellent. However, the key is exposed to the WebView JS (`CodeNative.localKey()`). Any XSS in the (offline) HTML would leak it.
   - Cleartext traffic is enabled (required for local 127.0.0.1). Fine for this use-case.
   - No certificate pinning or additional origin checks beyond host match.

7. **Process / resource management**
   - Watchdog shell is clever but relies on `/system/bin/sh` and `/proc`. Works on most devices; fails on heavily restricted or non-standard ROMs.
   - No explicit memory pressure handling when loading large GGUFs. `largeHeap=true` helps but is not a guarantee.
   - KV-cache fallback (`q8_0` → `f16`) is good; the `safeKv` flag + preference persistence is thoughtful.

8. **GBNF / GrammarEngine**
   - Excellent design (raw language grammar for blank files vs ordered-key edit envelope).
   - Some language grammars are overly permissive (e.g. Python just forbids backticks). Still far better than unconstrained generation.
   - Syntax gate + optional Termux AST is a strong self-heal loop.

9. **WebView / Monaco**
   - Virtual HTTPS origin + `shouldInterceptRequest` is the correct modern pattern for offline Monaco workers.
   - `getWorkerUrl` blob construction is correct.
   - AMOLED theme, accessory bar, multi-tab + IndexedDB drafts are polished mobile UX.

## Medium / Maintainability Issues

10. **No README, LICENSE, or .gitignore** in the provided layout.
11. **Hard-coded ports / constants** (9190) – document or make configurable.
12. **No unit / instrumentation tests**.
13. **Deprecated APIs** throughout (`onActivityResult`, `onBackPressed`, `startActivityForResult`).
14. **FileTools** does not implement directory rename, recursive copy, or proper symlink handling beyond the safe check.
15. **index.html** is a single 1400+ line monolith. Harder to maintain; consider modularizing later.

## Positive Highlights

- Zero-copy GGUF path resolution via `/proc/self/fd` + DocumentId + name+size search is sophisticated and practical.
- File-type reinforced GBNF + ordered `"search"`/`"replace"` is one of the better constrained-edit designs for local models.
- Review gate + step chain + automatic syntax self-heal is excellent UX for mobile AI coding.
- Offline-first Monaco + native llama-server is a compelling product direction.

## Recommended Immediate Fixes Before First CI Run

1. Add to CI before cmake:
   ```yaml
   - run: sudo apt-get update && sudo apt-get install -y ninja-build cmake
   ```
2. Make llama.cpp tag configurable or pin a known-good commit SHA.
3. Add a minimal `README.md` with build & permission instructions.
4. Add `.gitignore` for `android/.gradle`, `android/app/build`, `llama.cpp`, etc.
5. Consider switching debug build to `isDebuggable = true` for development.
6. Replace deprecated Activity result / back-press APIs when targeting newer SDKs.

## Verdict

**Usable foundation** after the critical CI and typo fixes. Architecture is strong; implementation details need hardening for reliable builds and long-term maintenance.
