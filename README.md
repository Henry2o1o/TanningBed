# Solarbank Cloud (Android)

Cloud-only Android app for an Anker SOLIX account. It does not use Modbus TCP or a local device connection.

The app signs in to the unofficial Anker SOLIX cloud API, displays account systems, and groups the returned devices by system assignment. It also shows important power readings when the cloud provides them. The Anker cloud may not provide telemetry for Solarbank 4 Pro, and available values can be delayed. This version does not promise live power readings.

The password is used only for the sign-in request and is not saved. The unofficial cloud interface can change at any time.

## Build APK

Open the project in Android Studio and choose **Build > Build APK(s)**. Or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
