# Smart Ball Smasher Android prototype (V0.1)

This is Android source code, **not a compiled APK**. The build workflow can compile
a debug APK using GitHub Actions, so a computer is not required if you can use
GitHub from your phone. The prototype uses Android screen-capture permission
(MediaProjection) and Accessibility permission (gesture tap).

## Build without a PC (using GitHub mobile browser)
1. Sign in to GitHub and create a new empty repository.
2. Upload the contents of this ZIP to the repository (keep folder structure).
3. Open the repository's **Actions** tab and run **Build Android APK**.
4. When it finishes, open the workflow run and download the
   `SmartBallSmasher-debug-apk` artifact.
5. Extract it on your phone and install `app-debug.apk`. Android may ask you
   to allow installation from that browser/file manager.

## First use
1. Open Smart Ball Smasher.
2. Tap **Enable tap accessibility** and enable its service in Android Settings.
3. Return and tap **Grant screen capture & start**. Approve the Android screen
   capture prompt.
4. Switch to Snapchat and open the game.
5. Stop the service from the app when finished.

## Important
- V0.1 is an uncalibrated prototype, not a proven winning bot. It detects orange
  pixels and estimates vertical movement. The game's art may cause false
  detections; it may tap at the wrong time.
- Test carefully and stop immediately if it taps incorrectly.
- It assumes the game's tap control is at bottom-centre. Different screen
  layouts need calibration.
- It does not read Snapchat account data or bypass protections.
- Android may restrict background capture or accessibility operation. The app
  must remain authorized and the game visible.
