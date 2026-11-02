# Shared debug keystore — status: PENDING (no real keystore exists yet)

## The problem

Every CI run currently generates a fresh debug keystore, so every new
APK has a different signature and will NOT install over the previous
one — the old app must be uninstalled first (which deletes its local
workflows). A shared debug keystore fixes this: every build signed with
the same key upgrades cleanly.

## What is already done (plumbing only)

- `app/build.gradle.kts`: the `debug` signingConfig uses
  `app/debug.keystore` when that file exists AND the environment
  variables `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS` and
  `DEBUG_KEY_PASSWORD` are set. Otherwise it falls back to the default
  per-machine debug keystore, exactly as before.
- `.github/workflows/android.yml`: a step decodes the
  `DEBUG_KEYSTORE_B64` repository secret into `app/debug.keystore`
  when the secret exists, and passes the three credential secrets to
  the build. When the secrets are absent the step is a no-op.
- `.gitignore`: `app/debug.keystore` is ignored — the real key file
  must never be committed to the repo.

## What is still missing (the exact next step)

No real keystore file has been created: this build environment has no
`keytool`, and no key file, password or base64 blob has been invented
or faked. To finish this, on ANY machine with a JDK (`keytool`):

1. Generate the key (pick a password; the values below are placeholders,
   use real ones and keep them private):
   ```
   keytool -genkeypair -v -keystore debug.keystore \
     -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass YOUR_STORE_PASSWORD -keypass YOUR_KEY_PASSWORD \
     -dname "CN=Android Debug, O=Android, C=US"
   ```
2. Encode it (Linux/macOS):
   ```
   base64 -w0 debug.keystore
   ```
   (On macOS: `base64 -i debug.keystore`.)
3. In GitHub, go to the repo → Settings → Secrets and variables →
   Actions → New repository secret, and add all four:
   - `DEBUG_KEYSTORE_B64` = the base64 output from step 2
   - `DEBUG_KEYSTORE_PASSWORD` = the store password from step 1
   - `DEBUG_KEY_ALIAS` = `androiddebugkey`
   - `DEBUG_KEY_PASSWORD` = the key password from step 1
4. Push any commit (or re-run the workflow). The first APK built after
   the secrets exist will use the shared key.

## Important caveats (read before doing this)

- This is a DEBUG key only — never use it for a release build, and
  never reuse a release key here.
- The FIRST APK built with the shared key will still require one
  uninstall of the previous differently-signed APK. Every APK after
  that upgrades cleanly, as long as the same keystore and secrets stay
  in place.
- Anyone with the keystore + passwords can sign APK updates for this
  app. The keystore file and the base64 text must be treated like a
  password: do not paste them in chat, do not commit them, do not
  email them.
- Until steps 1–3 are done, the keystore status stays PENDING and
  each new APK still needs the old app uninstalled first.
