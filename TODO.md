# TODO

## OTA update

Status: initial GitHub Releases implementation complete on `feat/ota-update`

Current configuration:

- Set `poldock.githubRepository=owner/repository` as a Gradle property, or set the
  `POLDOCK_GITHUB_REPOSITORY` environment variable.
- If neither is set, the build reads a GitHub repository from `remote.origin.url`.
- If no repository can be found, update checks stay disabled.
- The GitHub repository and its releases must be publicly readable. Do not embed a
  private GitHub token in the APK.
- Use a release tag newer than the app `versionName`, attach an APK asset, and build
  it with the same signing certificate as the installed app.

Provisional flow:

1. Publish a signed release APK in GitHub Releases.
2. When PolDock starts, check the latest release metadata.
3. Compare the installed `versionCode` with the latest release.
4. Notify the user when an update is available.
5. Download the APK after the user confirms.
6. Verify the downloaded APK package, version, and signing certificate.
7. Open the system file explorer at the downloaded APK location.
8. Let the user select the APK and install it manually.

Open decisions:

- Whether to keep GitHub Releases or use a separate update server
- Whether a checksum file should supplement APK signature validation
- Download behavior on metered/mobile networks
- Failure, cancellation, retry, and rollback behavior
- Whether the vehicle permits opening the system file explorer and installing an APK selected by the user
- Whether installation can continue across app restarts or vehicle power cycles

Completion criteria:

- No prompt when the installed version is current
- A single clear notification when a newer version is available
- Download starts only after explicit user confirmation
- PolDock does not request installation directly
- After download, the system file explorer opens at the APK location
- Installation proceeds only when the user selects the APK and confirms it manually
- Invalid, incomplete, or incorrectly signed APKs are rejected
- The current version remains usable if update download or installation fails
- Behavior is verified on both the emulator and the vehicle
