# Cloud sync setup (favourites + watch history across devices)

The app is **local-first**: it works fully offline with no Firebase. Cloud sync activates only once
you drop a `google-services.json` into `app/`. Until then the sync layer is a safe no-op.

## One-time setup (~10 min)

1. **Firebase Console → Add project.** IMPORTANT: choose **"Add Firebase to an existing Google
   Cloud project"** and pick the SAME project you made the OAuth clients in (project number
   `1087089172501`). This makes Firebase accept the Google ID token the app already obtains.
2. **Add an Android app** to the Firebase project:
   - Package name: `com.slickstream`
   - Debug SHA-1: `5A:10:ED:A9:32:BD:1A:F6:29:CF:C5:DC:52:DB:9C:1D:78:C9:E9:3C`
3. **Download `google-services.json`** and place it at `app/google-services.json`.
   (The Gradle build auto-detects it and applies the google-services plugin — no other change.)
4. **Authentication → Sign-in method → enable Google.**
5. **Firestore Database → Create database** (Production mode), then set these rules
   (Firestore → Rules):

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{uid}/{document=**} {
         allow read, write: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```

6. Rebuild + install:
   ```sh
   JAVA_HOME=~/android-dev/jdk-17.0.19+10 ANDROID_HOME=~/Android/Sdk ./gradlew installDebug
   ```

## How it works

- On Google sign-in the app exchanges the ID token for a Firebase Auth session, so data is keyed by
  the Firebase `uid`.
- Layout: `users/{uid}/favorites/{TYPE_id}` and `users/{uid}/history/{TYPE_id_season_episode}`.
- On sign-in: pull remote → merge into local (favourites add-if-missing; history takes the newer
  `updatedAt`), then push the local union up.
- Ongoing while signed in: local favourite add/remove → pushed; history → pushed (debounced 15 s);
  a live listener brings favourites added on other devices down.

## Known limitations (v1)

- **Favourite *removals* don't propagate across devices** (union semantics) — a removal on device A
  won't delete it from device B's local DB. Re-adds/additions and all history *do* sync.
- Live cross-device updates are additive for favourites; a full reconcile happens on next sign-in.
- History pushes are debounced (15 s) to stay within Firestore free-tier quotas.
