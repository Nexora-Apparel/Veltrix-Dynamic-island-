# Veltrix Island

An Android Dynamic Island-style overlay built with Kotlin and Jetpack Compose.

## Build a debug APK

GitHub Actions builds the debug APK on every push and supports manual runs from the **Actions** tab. The artifact is published as `island-apk`.

## Requirements

- Android SDK 35
- JDK 17
- Gradle 8.9

## Notes

The app uses overlay, foreground-service, notification-listener, accessibility-service, and boot permissions. Grant only the permissions needed for the features you use.