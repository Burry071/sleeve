# Release signing

Release builds sign automatically when `keystore.properties` exists at the repo
root (already gitignored). CI reads the same values from repository secrets.

## Local

1. Generate a keystore once (keep it private, back it up — losing it means a
   new app identity on stores):

   ```bash
   keytool -genkeypair -v -keystore keystore.jks \
     -alias sleeve -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` at the repo root:

   ```properties
   storeFile=keystore.jks
   storePassword=<your store password>
   keyAlias=sleeve
   keyPassword=<your key password>
   ```

3. `./gradlew :app:assembleGenericRelease` now produces a signed APK.

## GitHub Actions (tagged releases)

Set four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `SLEEVE_KEYSTORE_BASE64` | `base64 -w0 keystore.jks` output |
| `SLEEVE_STORE_PASSWORD` | store password |
| `SLEEVE_KEY_ALIAS` | `sleeve` |
| `SLEEVE_KEY_PASSWORD` | key password |

Pushing a tag (`v2.0.0-beta.1`) then builds, signs, and publishes a GitHub
Release with the APK attached. Without the secrets the release job still
succeeds and publishes an unsigned APK.
