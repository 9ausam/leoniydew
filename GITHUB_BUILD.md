# GitHub Actions se build (Android APK, iOS IPA, Windows EXE) — sirf no-sounds

Workflow: `.github/workflows/build.yml`

| Platform | Output file | Kahan milega |
|---|---|---|
| Android | `leoniydew-android-<version>-arm-base.apk` | Actions artifact `android-apk` |
| iOS | `leoniydew-ios-<version>-no-sounds.ipa` (unsigned) | Actions artifact `ios-ipa` |
| Windows | `leoniydew-<tag>-windows-x86_64-no-sounds.zip` (andar `leoniydew-native.exe`) | Actions artifact `windows-zip` |

Sounds APK/IPA/ZIP ke andar nahi hote. App unhe repo ke `sounds-<version>` release se download karta hai
(`sounds/release.txt` = `0.1.4`, yaani release tag `sounds-0.1.4` aapke repo me hona chahiye).

## Setup (ek baar)

1. GitHub par naya repository banao (naam: `leoniydew`). Agar naam/owner `MausamModz/leoniydew` se alag hai to
   `src/update.rs` me `MausamModz/leoniydew` wali URLs apne username/repo se badal dena.
2. Is poore folder ko repo me push karo (hidden `.github` folder zaroor jaye).
3. Repo > **Settings > Actions > General > Workflow permissions** > **Read and write permissions** > Save.
4. (Optional par recommended) Android signing secrets, taaki har build ek hi key se sign ho aur update install ho sake:
   Termux me:
   ```
   pkg install openjdk-21
   keytool -genkeypair -keystore leoniydew-release.jks -alias leoniydew -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 leoniydew-release.jks
   ```
   Repo > **Settings > Secrets and variables > Actions > New repository secret** me ye 4 secrets banao:
   - `ANDROID_KEYSTORE_BASE64` = upar `base64` ka poora output
   - `ANDROID_KEYSTORE_PASSWORD` = keystore password
   - `ANDROID_KEY_ALIAS` = `leoniydew`
   - `ANDROID_KEY_PASSWORD` = key password (PKCS12 me aksar keystore password jaisa hi)

   Secrets na ho to workflow us run ke liye temporary key banata hai (APK install ho jata hai, par agle build ko
   uske upar update nahi kar sakte; pehle uninstall karna padega). **`.jks` file kabhi repo me commit mat karo.**

## Build chalana

**Manual:** repo > **Actions > Build (Android, iOS, Windows) > Run workflow**. 30–60 min lagte hain (pehli baar zyada).
Khatam hone par run ka page kholo, neeche **Artifacts** se 3 files download karo.

**Release ke saath:** tag push karo, files GitHub Release me bhi lag jayengi:
```
git tag v0.5.5
git push origin v0.5.5
```

## Notes

- iOS IPA unsigned hai: AltStore / SideStore / Sideloadly se apni Apple ID se install karo.
- Windows ka AVX2 build chahiye ho to `build.yml` me windows job ke `cargo build` step par
  `env: RUSTFLAGS: -Ctarget-cpu=x86-64-v3` lagao.
- `android/gradle.properties` se Termux wali `aapt2FromMavenOverride` line hata di gayi hai (GitHub runner par wo
  path nahi hota, build fail hota). Termux par local build karna ho to ye line `~/.gradle/gradle.properties` me rakho.
