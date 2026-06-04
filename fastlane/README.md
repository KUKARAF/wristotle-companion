# Fastlane metadata

F-Droid (and Triple-T Gradle Play Publisher, hypothetically) read app
listing metadata from this directory. Layout:

```
fastlane/metadata/android/en-US/
├── short_description.txt        max 80 chars, no trailing period
├── full_description.txt         marketing description, plaintext
├── images/
│   ├── icon.png                 512x512 launcher icon
│   └── phoneScreenshots/        16:9 phone screenshots, named 1.png, 2.png …
└── changelogs/
    └── <versionCode>.txt        per-release notes, max 500 chars
```

Workflow per release:

1. Bump `app/build.gradle.kts` `versionCode` / `versionName` defaults.
2. Add `fastlane/metadata/android/en-US/changelogs/<new versionCode>.txt`
   summarising user-visible changes (or "internals-only patch — see
   https://wristotle.codeberg.page/changelog/" for fix-only releases).
3. Tag the commit. CI publishes to Codeberg releases; F-Droid's scanner
   picks the tag up via `UpdateCheckMode: Tags`.
