# Encrypted Photo Vault (EPV1)

**A working, tested Python CLI plus an Android Studio project (Android APK not built in this environment).**

EPV1 stores arbitrary files (photos, videos, documents) as *real RGB PNG images*: each PNG's pixels encode an AES-256-GCM encrypted chunk of the original file. There is no JPEG or reversible visual information from the source photo. A random file ID, Argon2id salt, and per-part AES-GCM nonce make repeated encryption produce different PNGs and different hashes. File names, original type, sizes, and SHA-256 are authenticated and encrypted in every part; only file ID, part index/count, size of ciphertext, nonce, salt and KDF parameters remain visible.

The format has **no extra database**: the PNGs themselves contain the needed reconstruction information. Each file is split into 8 MiB chunks. All chunks must be present to recover a file. Integrity is enforced by GCM authentication and final SHA-256 verification.

## Windows / macOS / Linux

Install Python 3.10+ and from this directory:

```powershell
py -m pip install -r requirements.txt
py -m epv encrypt "C:\Users\You\Pictures" -o "C:\EPV_PNG"
py -m epv inspect "C:\EPV_PNG"
py -m epv cloudcheck "C:\EPV_PNG" "C:\Downloaded_From_Google_Photos"
py -m epv restore "C:\EPV_PNG" -o "C:\Recovered"
```

For a windowed Windows application, double-click `Start-EPV-Windows.bat`. On Linux/macOS replace `py` with `python3`. Commands prompt for a password without echoing it. **Use the exact same password to restore.** Never pass the password on the command line. For automation only, `--password-env EPV_PASSWORD` reads it from the process environment; the variable itself has exposure risks.

To validate an actual Google Photos upload/download, retain the pre-upload PNG folder, download a copy into a second folder, and run `epv cloudcheck` as shown above. It compares the **RGB pixel bytes**, allowing differences in irrelevant PNG metadata.

Test the format locally:

```bash
python -m pip install -r requirements.txt pytest
python -m pytest -q tests
```

## Android app (Kotlin / Compose)

The Android source is under `android/`. It targets Android 10+ (API 29), uses Android's file picker and MediaStore, and **requires no root or broad storage permission**.

1. Open `android/` in Android Studio and sync Gradle, or upload the repo to GitHub and run **Actions → Android debug APK → Run workflow**. The artifact contains `app-debug.apk`.
2. Install the debug APK, enter a sufficiently strong password, tap **Выбрать файлы и зашифровать**.
3. Encoded images are saved in the indexed Android image folder `Pictures/EncryptedPhotoVault`.
4. On Pixel XL (original generation), transfer the PNG folder through USB/Syncthing/another method. In **Google Photos → Backup → Back up device folders**, enable `EncryptedPhotoVault`. The original Pixel is officially eligible for unlimited Original quality backup.
5. To preview encrypted images/videos without exporting plaintext, use **Открыть зашифрованную галерею** and select the downloaded PNG directory. To permanently restore originals, use **Выбрать папку с PNG**; files are written to `Download/EncryptedPhotoVault`.

**Warning:** `Download/EncryptedPhotoVault` contains *unencrypted* recovered files. Do not restore onto an untrusted/shared device. PNG images in Google Photos will visually look like random noise.

This is an MVP with an encrypted local gallery: decrypted photos are displayed in RAM, and videos can be played with an Android `MediaDataSource` that decrypts PNG chunks on demand (no plaintext media export required). There is still no background transfer to the Pixel, no Google Photos API upload, and no automatic download/backup monitoring. The Android APK still needs a real Android build and device testing, while the Java crypto core is tested against the Python format on the JVM.

## Integrity/security warnings

- **Keep your original files** until you have tested uploading, downloading, and restoring several examples end-to-end. Pixel's Original quality policy does not by itself guarantee that exported pixels will always match.
- A lost password cannot be recovered. Use a strong unique passphrase and store a copy offline.
- This is not audited software. Don't use as the only archive of important files.
- Keep a separate original and independent backup; Google Photos may remove or modify content.
- Avoid uploading PNGs via chat or photo editors that may recompress/resize them.
- No information about original image content is deliberately stored unencrypted. **The number and approximate sizes of PNGs are not hidden**.

## Format EPV1, big endian

Raw RGB bytes are read left-to-right/top-to-bottom, 3 bytes per pixel. Image dimensions vary from 512×512 to 2048×2048. The first 76 RGB bytes are:

| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 4 | ASCII `EPV1` |
| 4 | 1 | version `1` |
| 5 | 1 | flags `0` |
| 6 | 2 | header length `76`, big endian |
| 8 | 16 | random file UUID-like ID |
| 24 | 4 | zero-based part index |
| 28 | 4 | total parts |
| 32 | 16 | Argon2id salt |
| 48 | 12 | random AES-GCM nonce |
| 60 | 4 | ciphertext size including GCM tag |
| 64 | 4 | Argon2id memory KiB = 65536 |
| 68 | 4 | Argon2id iterations = 3 |
| 72 | 4 | Argon2id parallelism = 1 |

Key: Argon2id v1.3, UTF-8 password, 64 MiB RAM, iterations 3, parallelism 1, 32-byte output. AES-256-GCM with 16-byte tag; entire 76-byte header is authenticated associated data (AAD). Plaintext of each part is:

```
uint32_be metadata_json_len
UTF-8 metadata JSON: {"n":"original.ext","s":size_in_bytes,"h":sha256_lowerhex,"m":"mime/type"}
original bytes chunk (<= 8 MiB)
```

Random trailing RGB bytes fill the final pixel row, ignored by decoder. PNGs have no original-file EXIF metadata. Decoders must verify part completeness, authentic GCM tags, consistent metadata, final file size and SHA-256; never overwrite an existing result. Name/path safety checks required.

## Further development

Potential next milestones: thumbnail grid and album UI, reliable Pixel XL sync, download restore UI, memory-safe image browsing at scale, and secure deletion of exported files. The MVP does **not** try steganography or visual camouflage.
