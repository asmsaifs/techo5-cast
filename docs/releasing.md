# Releasing

## One time

**A signing key.** Every update of the app has to be signed with the same key, so make it once and keep it
safe: lose it and nobody can update, they have to uninstall (losing their saved Shows) and install again.

```sh
keytool -genkeypair -v -keystore techo5-cast-release.jks -alias techo5cast \
    -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` and its passwords in a password manager and a backup, and **never commit them** (`*.jks`,
`*.keystore` and `keystore.properties` are git-ignored).

**GitHub secrets** (repository settings → Secrets and variables → Actions), for the release workflow:

| Secret | Value |
|---|---|
| `CAST_KEYSTORE_BASE64` | `base64 -i techo5-cast-release.jks` (on Linux `base64 -w0 techo5-cast-release.jks`) |
| `CAST_KEYSTORE_PASSWORD` | the store password |
| `CAST_KEY_ALIAS` | `techo5cast` |
| `CAST_KEY_PASSWORD` | the key password |

**The application id.** It is `dev.techo5.cast.app` (`android/app/build.gradle.kts`). It can never change
after the first release without every user having to reinstall, so settle it before the first tag. The
plan's alternative was `io.github.<account>.techo5cast`.

## A release

1. Set what is new in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. The version code is
   `major × 10000 + minor × 100 + patch`, so `0.1.0` is `100` and `1.2.3` is `10203`. The release notes
   are that file.
2. Update `fdroid/dev.techo5.cast.app.yml` if you keep it in step.
3. Make sure `main` is green (CI runs the Go tests, the protocol interop tests, the engine tests, lint and a
   debug build).
4. Tag and push:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

The release workflow then builds four signed APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64` and a `universal`
one), names them `techo5-cast-<version>-<abi>.apk`, writes `SHA256SUMS`, and publishes a GitHub Release.
A tag with a suffix (`v0.2.0-rc1`) works the same and uses the numbers before the dash.

### A signed build on your own machine

Put the key's details in `android/keystore.properties` (git-ignored):

```properties
storeFile=/path/to/techo5-cast-release.jks
storePassword=...
keyAlias=techo5cast
keyPassword=...
```

then `cd android && ./gradlew :app:assembleRelease -PversionName=0.1.0 -PversionCode=100`. The APKs are in
`app/build/outputs/apk/release/`. Without a key the same command makes `…-release-unsigned.apk` files.

A release APK is signed with a different key from a debug build, so Android will not install one over the
other: uninstall first.

## Where the app can be distributed

- **GitHub Releases** (what the workflow does). This is the primary route.
- **Not Google Play.** Fetching YouTube streams outside YouTube's own player breaks YouTube's terms and
  Play's policies. The README says so.
- **F-Droid's main repository: probably not as it is.** F-Droid builds everything from source, and
  youtubedl-android ships prebuilt Python and FFmpeg runtimes, which it does not accept. `fdroid/` has a
  draft recipe and `fastlane/` the store text (no screenshots yet), for when that changes. Two ways round it:
  replace the `Extractor` implementation in `extract/` with NewPipeExtractor (source-built, YouTube-focused,
  needs an app release whenever YouTube changes; the `Extractor` interface is there for this), or offer the
  app from [IzzyOnDroid](https://gitlab.com/IzzyOnDroid/repo) or your own F-Droid repository, which take
  prebuilt binaries.

## The Show side

The app needs the receiver in `echod/internal/feature/cast` (the techo5 repository). It is in `main` there, but
a Show only has it once a TECHO5 release that includes it is installed in a slot. Until then it can be tried
on a running Show by copying a built daemon over and binding it until the next reboot
([techo5 docs/building.md](https://github.com/asmsaifs/techo5/blob/main/docs/building.md), section 3; the
steps and the no-sftp workaround are also in [techo5 docs/cast.md](https://github.com/asmsaifs/techo5/blob/main/docs/cast.md)).
Note the Show has no sftp server, so use `ssh root@<address> 'cat > /tmp/echod-test' < bin/echod-arm` where
`scp` fails.

## Before you tag: what to check on a phone

The automated tests cannot cover these; do them on a real phone and a real Show, from the signed release APK:

- Add a Show by scanning its code; Check connection; the Show asks to Accept.
- Share a YouTube video (and one from another site): plays in seconds, pause/seek/stop from the notification.
- Cast a file from the picker, and one shared from Gallery.
- Mirror the screen with sound; rotate; stop from the notification and from the tile.
- Turn Wi-Fi off for 10 s mid-cast: it carries on.
- A protected video: the app says it can't be cast, not a stack trace.
