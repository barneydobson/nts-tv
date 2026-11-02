# Security

## Reporting a problem

Please report security issues privately through GitHub: on this repository's **Security** tab, choose **Report a vulnerability**. Don't open a public issue.

## Checking a download is genuine

Every release is signed with the same key. Its certificate SHA-256 fingerprint is:

```
B2:93:53:41:AC:25:AD:94:C2:18:F4:BF:CA:EF:D1:B9:A1:2A:62:E7:C0:7D:3F:CB:0F:F6:39:07:F1:00:00:21
```

To check an APK on a computer with the Android SDK build tools installed:

```bash
apksigner verify --print-certs nts-tv.apk
```

The `certificate SHA-256 digest` it prints should match the fingerprint above (without colons, lower case). Android also refuses to install an update signed with a different key over an existing install.

## What the app does and doesn't do

- It only connects to NTS's own servers (`nts.live`, `*.ntslive.net`, `*.ntslive.co.uk`) and, to check for updates, GitHub (`github.com`, `*.githubusercontent.com`), and only over HTTPS.
- It stores nothing on the device and has no accounts, analytics or tracking.
- Its permissions are internet access, keeping playback running in the background, and installing its own updates.

## How updates are checked

The app only installs an update that:

1. comes from this repository's latest GitHub release (`github.com/barneydobson/nts-tv/releases`),
2. is this app (`io.github.barneydobson.ntstv`) at a newer version, and
3. is signed with the release certificate above.

Android separately refuses any update not signed with the same key as the installed app. So publishing a malicious update would take both write access to this repository and the signing key.
