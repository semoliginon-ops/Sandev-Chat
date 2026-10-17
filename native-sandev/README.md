# Sandev Chat — Native Rewrite

This directory is the clean Android-native rewrite. It intentionally sits outside the legacy PocketPal workflow paths so source preparation does not trigger an APK build.

## Direction

- Kotlin Android UI; no React Native, WebView, HTML bridge, or llama.rn.
- One inference engine: the official llama.cpp Android binding, pinned to upstream commit `f74e2fa6a31cc33ab4ee4ae516641cfe62df3924`.
- Target `arm64-v8a` only for the Samsung Galaxy A05 test device and to avoid bundling unnecessary ABIs.
- Model files are copied into private app storage. The active model is remembered and automatically loaded on next launch; users should not need to re-import or manually select it each time.
- Model import, selection, and deletion are implemented in the native model menu.
- The UI remains plain Android views to keep dependencies and rendering overhead low.

## Prepare the native engine source

The upstream llama.cpp source is intentionally not vendored into this repository. Before a future approved build, run:

```sh
./scripts/prepare-engine.sh
```

This clones the pinned upstream source into `third_party/llama.cpp`. Gradle then consumes the official Android `lib` module from that checkout.

## Build approval

Do not build an APK automatically as part of source preparation. Build only after Joni explicitly approves a build. The existing PocketPal workflow is left untouched and this directory is not among its watched paths.

## Feature checklist

- [x] Native chat screen and streaming answer display
- [x] Import multiple GGUF files into app-private storage
- [x] Remember active model and auto-load it after restart
- [x] Select and delete stored models with confirmation
- [x] Save chat history locally
- [x] Create/select simple bots with a system prompt
- [x] Choose a bot/profile picture
- [x] Choose a chat background image
- [x] Change and persist accent themes
- [x] Choose and play/pause a local music file

This is source preparation only. No APK has been built or device-tested yet.
