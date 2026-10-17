# S3 Media Backup (Android)

An Android app that backs up the photos and movies on your phone to an Amazon S3
bucket. It is write-only: it uploads new or changed media and never reads objects
back or deletes them.

- **Kotlin + Jetpack Compose + Material 3**
- **Amazon Cognito Identity Pool** for credentials (no AWS keys stored on the device)
- **WorkManager** for "back up now" and for a repeating background schedule
- Uploads stream straight from `ContentResolver` to S3 — **no temp copies**, so a
  20 GB movie does not need 20 GB of free space

---

## Contents

1. [How it works](#how-it-works)
2. [What is on this machine already](#what-is-on-this-machine-already)
3. [Step 1 — AWS setup](#step-1--aws-setup)
4. [Step 2 — Build](#step-2--build)
5. [Step 3 — Install on your phone](#step-3--install-on-your-phone)
6. [Step 4 — Configure and run](#step-4--configure-and-run)
7. [Object layout in S3](#object-layout-in-s3)
8. [Security notes](#security-notes)
9. [Troubleshooting](#troubleshooting)

---

## How it works

```
┌──────────────┐   MediaStore scan   ┌──────────────────┐
│  Photos and  │ ──────────────────► │ BackupRepository │
│  movies      │                     │  · diff vs local │
└──────────────┘                     │    Room index    │
                                     └────────┬─────────┘
                                              │ per file
                                     ┌────────▼─────────┐
                                     │    S3Uploader    │
                                     │ ≤64 MB: PutObject│
                                     │  >64 MB: multipart│
                                     └────────┬─────────┘
                                              │ streaming, signed
                                     ┌────────▼─────────┐
                                     │   Amazon S3      │
                                     └──────────────────┘
```

| Concern | How it is handled |
| --- | --- |
| Credentials | `CognitoCachingCredentialsProvider` exchanges a device identity for short-lived STS credentials, cached locally and refreshed automatically |
| What has already been uploaded | A Room table keyed on the MediaStore id + a size/timestamp fingerprint. Changed files are re-uploaded, unchanged ones are skipped |
| Large movies | Multipart upload with per-part retry and an abort on failure. Parts are bounded so memory use never grows with file size |
| Background limits | `CoroutineWorker` runs as a `dataSync` foreground service so Android 14+ does not kill an in-flight upload |
| Retries | 3 attempts with exponential backoff on network errors, HTTP 5xx and throttling. The media stream is re-opened on every attempt so a retry never resends a half-read body |

### What it does *not* do

- It does not delete S3 objects, and cannot read them back.
- It does not restore media to the device.
- It does not de-duplicate identical photos with different MediaStore ids.
- Background runs are best-effort: Android Doze can delay a periodic job.

---

## What is on this machine already

Installed during setup:

| Tool | Location |
| --- | --- |
| JDK 17 (Zulu) | `/Library/Java/JavaVirtualMachines/zulu-17.jdk` |
| Android SDK command-line tools | `/opt/homebrew/share/android-commandlinetools` |
| Android SDK platform 35, build-tools 35.0.0, platform-tools | under the SDK root |
| Gradle 8.11.1 wrapper | `./gradlew` in this project (no global Gradle needed) |

`local.properties` already points at the SDK. If you move the SDK, edit that file.

Set these before running any Gradle or `adb` command:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
```

---

## Step 1 — AWS setup

You need an S3 bucket and a Cognito Identity Pool that the app can use.

### 1a. Bucket

Use an existing bucket or create one:

```bash
aws s3api create-bucket \
  --bucket YOUR_BUCKET_NAME \
  --region ap-northeast-1 \
  --create-bucket-configuration LocationConstraint=ap-northeast-1
```

Leave **Block all public access** turned on. Bucket-owner-enforced object ownership
is fine and recommended.

### 1b. Identity Pool + IAM role

The template in [`aws/cognito-identity-pool.yaml`](aws/cognito-identity-pool.yaml)
creates:

- a Cognito Identity Pool with guest (unauthenticated) access
- an IAM role that can **write only**, and only under the prefix you choose

> **Region matters.** Create the stack in the *same* region as the bucket, and use
> that region in the app's Settings screen. The app points its S3 client at a single
> regional endpoint, so a bucket in a different region will fail with a `301`.

#### Option 1 — AWS CloudShell (recommended, nothing to install)

CloudShell is already authenticated with your console login, so there are no
credentials to manage.

1. In the AWS console, click the **CloudShell** icon in the top-right of the header
   bar (or open `console.aws.amazon.com/cloudshell`). Pick your bucket's region from
   the region selector first.
2. Get the template in. Either use **Actions → Upload file** in the CloudShell
   toolbar and choose `aws/cognito-identity-pool.yaml`, or just paste it:

   ```bash
   cat > cognito-identity-pool.yaml <<'TEMPLATE'
   ...paste the contents of aws/cognito-identity-pool.yaml here...
   TEMPLATE
   ```

3. Deploy:

   ```bash
   export BUCKET=your-bucket-name
   export PREFIX=media

   aws cloudformation deploy \
     --stack-name s3-media-backup \
     --template-file cognito-identity-pool.yaml \
     --capabilities CAPABILITY_NAMED_IAM \
     --parameter-overrides BucketName=$BUCKET KeyPrefix=$PREFIX
   ```

4. Read the Identity Pool ID you will paste into the app:

   ```bash
   aws cloudformation describe-stacks \
     --stack-name s3-media-backup \
     --query "Stacks[0].Outputs[?OutputKey=='IdentityPoolId'].OutputValue" \
     --output text
   ```

#### Option 2 — from your own machine

Install the CLI first (`brew install awscli`), then authenticate. The cleanest way
is IAM Identity Center, which gives auto-refreshing temporary credentials with no
secrets to copy:

```bash
aws configure sso
export AWS_PROFILE=your-profile
aws sso login
```

If you instead want to reuse a CloudShell session's credentials locally, run this
**in CloudShell** and copy the `export` lines it prints to your Mac:

```bash
python3 - <<'PYEOF'
import json, os, urllib.request

uri = os.environ.get("AWS_CONTAINER_CREDENTIALS_FULL_URI")
if uri:
    req = urllib.request.Request(uri)
    tok = os.environ.get("AWS_CONTAINER_AUTHORIZATION_TOKEN")
    if tok:
        req.add_header("Authorization", tok)
else:
    req = urllib.request.Request(
        "http://169.254.170.2" + os.environ["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"]
    )

c = json.load(urllib.request.urlopen(req))
region = os.environ.get("AWS_DEFAULT_REGION", os.environ.get("AWS_REGION", "us-east-1"))
print(f'export AWS_ACCESS_KEY_ID={c["AccessKeyId"]}')
print(f'export AWS_SECRET_ACCESS_KEY={c["SecretAccessKey"]}')
print(f'export AWS_SESSION_TOKEN={c["Token"]}')
print(f'export AWS_DEFAULT_REGION={region}')
print(f'export AWS_REGION={region}')
print(f'# expires at {c["Expiration"]}')
PYEOF
```

`aws sts get-session-token` does **not** work for this — STS will not mint a session
token from CloudShell's already-temporary credentials. Note the expiry line; re-run
the script when calls start failing with `ExpiredToken`.

The resulting policy allows exactly this and nothing more:

```json
{
  "Effect": "Allow",
  "Action": ["s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
  "Resource": "arn:aws:s3:::YOUR_BUCKET/media/*"
}
```

No `s3:GetObject`, no `s3:DeleteObject`, no listing outside `media/`.

---

## Step 2 — Build

```bash
cd s3-media-backup          # the repo root, wherever you cloned it

export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew assemble
```

Two APKs are produced:

| File | Notes |
| --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | 12 MB — use this one on your phone |
| `app/build/outputs/apk/debug/app-debug.apk` | 18 MB, with debug tooling |

Both are signed with this machine's debug key, so they install directly. Because
that key lives in `~/.android/debug.keystore`, you can push updated builds over the
top without uninstalling.

Useful targets:

```bash
./gradlew assembleDebug     # debug only
./gradlew assembleRelease   # release only
./gradlew lint              # static checks
./gradlew clean
```

---

## Step 3 — Install on your phone

### Option A — USB (fastest)

1. On the phone: **Settings → About phone → tap "Build number" 7 times** to enable
   Developer options.
2. **Settings → System → Developer options → USB debugging** → on.
3. Plug the phone in, choose **File transfer / Android Auto** when prompted.
4. On the phone, accept the **Allow USB debugging?** dialog.

Then:

```bash
adb devices -l
adb install -r app/build/outputs/apk/release/app-release.apk
```

`adb devices` must list your device as `device`. If it says `unauthorized`, tap
"Allow" on the phone.

### Option B — Wireless debugging (Android 11+)

1. **Settings → System → Developer options → Wireless debugging** → on.
2. Tap **Pair device with pairing code** and note the IP:port and the code.
3. On the Mac:

```bash
adb pair   IP_FROM_PAIRING_DIALOG:PORT
# enter the 6-digit code when prompted

adb connect IP_FROM_MAIN_WIRELESS_SCREEN:PORT
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Option C — No computer at all

Copy `app-release.apk` to the phone (AirDrop, Google Drive, USB) and open it with a
file manager. Android will ask you to allow that app to **install unknown apps**;
grant it once and install.

> Installing from a debug key means the app cannot be published to Play and cannot be
> updated by an APK signed with a different key. For personal use this is fine.

---

## Step 4 — Configure and run

1. Open **S3 Media Backup**.
2. Tap the **settings** icon (top right) and enter:
   - **Region** — the bucket's region, e.g. `ap-northeast-1`
   - **Cognito Identity Pool ID** — the CloudFormation output
   - **S3 bucket name**
   - **Key prefix** — defaults to `media`
3. Tap **Save**.
4. Back on the home screen, tap **Grant access**. The app requests **only** the
   permissions for the media types you enabled — turn photos off and it never asks for
   `READ_MEDIA_IMAGES` at all. On Android 14+ you can also grant "selected photos
   only"; the app backs up whatever it can see.
5. Tap **Back up now**.

### Backing up only videos

Photos and videos can be enabled independently under **Settings → What to back up**.
This is the right setup when your photos already live in an unlimited photo service
and only movies are worth paying S3 storage for:

| Setting | Value |
| --- | --- |
| Photos | off |
| Videos | on |

With photos off, the app holds no photo permission, skips image files entirely, and
reports counts for videos only.

> **Careful if you delete objects by hand.** Turning photos off stops *future* uploads
> but does not remove anything already in S3. If you run `aws s3 rm --recursive` to
> clear an already-uploaded prefix, also clear the app's data
> (**Settings → Apps → S3 Media Backup → Storage → Clear data**) before re-enabling
> that media type. Otherwise the local index will report those files as "already
> backed up" and skip them, leaving them missing from S3.

To automate it, turn on **Automatic backup** in settings and pick an interval.
The job runs only on Wi-Fi (and optionally only while charging) by default.

---

## Object layout in S3

```
media/
├── photos/
│   └── 2026/10/
│       ├── IMG_20261012_083012.jpg
│       └── PXL_20261014_221155.jpg
└── videos/
    └── 2026/10/
        └── VID_20261015_174203.mp4
```

Folders come from the photo's capture date. Every object gets a
`x-amz-meta-media-store-id` header matching the device's MediaStore id.

### Name collisions

Two different files in the same month can share a display name — two downloads both
called `image.jpg`, for instance. Writing both to the same key would silently
destroy one of them, so the second and later members of a collision group get a
`~<mediaStoreId>` suffix before the extension:

```
media/photos/2026/10/image.jpg        <- lowest MediaStore id, keeps the plain key
media/photos/2026/10/image~84721.jpg  <- the colliding one
```

The lowest id keeps the plain key so a name that is unique today does not change key
just because a same-named file is added later. Collisions are resolved across the
*whole* scan, so a new file can never land on the key of one already in S3.

This is covered by unit tests in
[`app/src/test/java/com/example/mediabackup/data/S3KeyResolutionTest.kt`](app/src/test/java/com/example/mediabackup/data/S3KeyResolutionTest.kt)
— run them with `./gradlew :app:test`.

### What gets skipped

Every uploaded file is recorded in a local Room table with a
`mediaStoreId:size:timestamp` fingerprint. On each run the MediaStore scan is diffed
against that table in memory:

| Situation | Result |
| --- | --- |
| File unchanged | Skipped — counted as "already backed up" |
| File edited (size or timestamp changed) | Re-uploaded to the same key |
| New photo or video | Uploaded |
| Backup interrupted mid-run | Resumes at the first unindexed file |
| App uninstalled / data cleared | Index lost, everything re-uploads (overwriting identical content — no duplicates, just bandwidth) |

Skipping uses only the local index, so a normal incremental run makes no S3
requests at all until it has something to upload.

---

## Security notes

**Read this before relying on the setup.**

1. **Guest access is a shared secret.** An unauthenticated Identity Pool means anyone
   who obtains the pool ID can upload objects into your prefix. The pool ID ships in
   the APK and is trivially extractable. This is the normal trade-off for a
   no-sign-in app, and it is why the IAM policy is scoped to `media/*` with
   write-only actions — a stranger can fill your bucket (costing you money) but
   cannot read, overwrite-policy, or delete anything else.

2. **If that is not acceptable**, tighten it in one of these ways:
   - Point the app at a small backend that issues **presigned PUT URLs** instead of
     using a Cognito Identity Pool. Then there is no shared write capability at all.
   - Add Cognito User Pool sign-in and drop `AllowUnauthenticatedIdentities` to
     `false`.
   - Add an S3 bucket policy with a condition such as `aws:PrincipalTag`, or an S3
     bucket-level **rate/quota** rule, or enable S3 **Block Public Access** plus a
     budget alarm.

3. **Cost.** The phone can upload unlimited data. Set an AWS billing alarm on the
   bucket's region. S3 PUT requests are $0.005 per 1,000 — negligible — but storage
   and data transfer are not free.

4. **No secrets on the device.** The Identity Pool ID and bucket name are not
   secrets; authorization lives entirely in the IAM role.

5. **The release APK is signed with a debug key.** Create a proper keystore before
   sharing the app with anyone else:

   ```bash
   keytool -genkey -v -keystore release.keystore -alias media-backup \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Then add a `signingConfigs.release` block in `app/build.gradle.kts`.

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `adb devices` is empty | Try a different cable (many are charge-only), or re-select **File transfer** on the phone |
| `unauthorized` in `adb devices` | Accept the debugging prompt on the phone; if none appears, revoke USB debugging authorisations and replug |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall the existing app first: `adb uninstall com.example.mediabackup` |
| Backup fails with `NotAuthorizedException` | Identity Pool ID is wrong, is in a different region, or guest access is disabled |
| `AccessDenied` on upload | Bucket name or region mismatch, or the IAM policy prefix does not match the app's key prefix |
| `Unknown AWS region` | Region must look like `ap-northeast-1` — lowercase, hyphenated, ending in a number |
| Backups stop in the background | Android battery optimisation. Long-press the app → **App info → Battery → Unrestricted** |
| A large video never finishes | Files above roughly 1 TB (9,000 × 128 MB parts) are rejected by design. Check `adb logcat -s BackupWorker` |
| Everything uploads twice after an app reinstall | The local Room index is cleared on uninstall. Objects are overwritten with identical content; nothing is corrupted |

Capture live logs while a backup runs:

```bash
adb logcat -c && adb logcat | grep -E "MediaBackup|AmazonServiceException|TransferService"
```

---

## Project layout

```
app/src/main/java/com/example/mediabackup/
├── BackupApp.kt              Application scope; owns the two singletons
├── MainActivity.kt           Compose entry point, permissions, navigation
├── data/
│   ├── BackupDatabase.kt     Room index of already-uploaded media
│   ├── BackupRepository.kt   Scan → diff → upload → report progress
│   ├── MediaItem.kt          One photo or video
│   ├── MediaScanner.kt       MediaStore queries (scoped-storage safe)
│   ├── Permissions.kt        Per-Android-version permission handling
│   ├── ProgressInputStream.kt  Byte counter for upload progress
│   ├── S3Uploader.kt         Cognito + S3: streaming PUT / multipart
│   └── SettingsRepository.kt SharedPreferences-backed settings
├── ui/
│   ├── BackupViewModel.kt
│   ├── HomeScreen.kt         Status, progress, "Back up now"
│   ├── SettingsScreen.kt     AWS + schedule configuration
│   └── theme/Theme.kt        Material 3, dynamic colour on Android 12+
└── work/
    ├── BackupWorker.kt       Foreground CoroutineWorker
    └── BackupScheduler.kt    WorkManager registration
```

---

## License

MIT — see [LICENSE](LICENSE).

Dependencies keep their own licenses (the AWS SDK for Android and AndroidX are both
Apache-2.0, which MIT projects may use freely).
