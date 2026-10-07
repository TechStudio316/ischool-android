# iSchool Android App

Android WebView application for https://i.schoolrms.com.ng

- App name: iSchool
- Package: ng.com.schoolrms.ischool
- Android Gradle Plugin: 8.7.3
- Gradle used by Codemagic: 8.9
- Java: 17
- compileSdk / targetSdk: 35

## Codemagic
The included `codemagic.yaml` downloads Gradle 8.9 on the build machine and builds the debug APK. This deliberately avoids depending on a Gradle Wrapper file that was missing from the original repository upload.

Expected artifact:
`app/build/outputs/apk/debug/app-debug.apk`

## Strict device licensing build
This build creates a persistent installation UUID in Android SharedPreferences and
sets first-party iSchool device cookies before loading the login page. Reinstalling
or clearing app data may create a new installation identity and can require an admin
device reset when the plan's device limit has been reached.

After Codemagic builds the APK, rename `app-debug.apk` to `ischool.apk` before
uploading it to the website's `/download/` directory.
