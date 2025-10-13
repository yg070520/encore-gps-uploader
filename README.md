# gps-uploader

Simple Android app (Java) for Android 8.0 car head-unit.
- package: com.sandy.cartracker
- app name: EncorePosUps
- Upload endpoint: https://i.jatus.top/encoreupload
- Behavior: starts on boot, runs a foreground service to upload GPS coordinates.

How to use:
1. Create a new repo on GitHub.
2. Upload (or push) the contents of this project to the repo.
3. Ensure branch `main` exists and push to it.
4. On push, GitHub Actions will build the APK; check Actions -> build job -> Artifacts -> gps-uploader-apk.
5. Download and install `app-debug.apk` on your car unit (via adb or SD card).

Notes:
- The CI uses `gradle/gradle-build-action` so a Gradle wrapper is not required.
- If your car unit doesn't have Google Play services, fused location provider may fail. In that case replace FusedLocationProvider with Android LocationManager.
