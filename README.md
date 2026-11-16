# compressor — Native Android App (Kotlin & Jetpack Compose)

A complete, standalone Android Studio project for local on-device PDF compression.

## Features Included
- **100% Local Processing**: Powered by `pdfbox-android` + Android `PdfRenderer`. No files leave the device.
- **Batch PDF Selection**: Modern Storage Access Framework (`OpenMultipleDocuments`) requiring zero storage permissions on Android 10–15.
- **High / Medium / Low Compression**: Selective image downsampling & stream optimization while preserving text, fonts, links, and page layout.
- **Pre-Compression Size Estimates**: Structural analysis before compression with clear non-guarantee notice.
- **Save to Any Folder**: Native `CreateDocument` system folder picker (`Save As…`), plus Open, Share (`FileProvider`), Rename, PDF Page Preview, and persistent Compression History.
- **Bilingual English (LTR) & Arabic (RTL)** + **Light / Dark / System** Material 3 theme.

## Build APK Automatically on GitHub
1. Extract this ZIP and push all files to a GitHub repository.
2. Open the **Actions** tab on GitHub — `.github/workflows/build-apk.yml` automatically builds `app-debug.apk` using Gradle 8.9 & JDK 17.
3. Download the compiled APK from the workflow's **Artifacts** section.

## Open in Android Studio
Open the extracted folder directly in **Android Studio Ladybug / Meerkat** (JDK 17, Compile SDK 35, Min SDK 26).
