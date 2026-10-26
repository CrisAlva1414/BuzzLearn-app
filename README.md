# BuzzLearn Android

Native Android client for **BuzzLearn**, built with Kotlin and Jetpack Compose.

This repository contains the Android client only. The API contract and product planning live in a separate private repository.

## Stack

- Kotlin 2.4 · JDK 21 · minSdk 26
- Jetpack Compose · Navigation 3
- Retrofit · OkHttp · kotlinx.serialization
- DataStore (refresh token)

## Implemented

- Typed client for the BuzzLearn auth API
- In-memory access token, single-flight refresh and rotation
- Durable sign-out with server-side revocation
- No telemetry or crash-reporting SDKs (`scripts/check-no-telemetry.sh`)

## Build

Set `ANDROID_HOME` to your Android SDK, then:

```bash
./gradlew test
./gradlew assembleDebug
```

Release builds need an HTTPS base URL:

```bash
BUZZLEARN_BASE_URL=https://api.example.com/ ./gradlew bundleRelease
```

## Status

The authentication/session core is complete. The UI is a minimal scaffold; feature screens are next.
