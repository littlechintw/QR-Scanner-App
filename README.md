# QR 掃描器

Android QR Code / Barcode 掃描 app。

## 功能

- **掃描**：CameraX 相機預覽，ML Kit 離線辨識，同一畫面可同時偵測**多個 QR code 與各式 barcode**（EAN、Code 128、Data Matrix、PDF417…）
- **自動擷取**：偵測到條碼且畫面穩定時自動拍下照片並儲存，也可手動按快門
- **掃描紀錄**：照片 + 所有條碼內容存在手機內（Room 資料庫 + 內部儲存空間），完全離線
- **結果檢視**：在照片上以編號框標出每個條碼的位置與內容，可複製、開啟連結

## 本機建置

需要 JDK 17 與 Android SDK（`local.properties` 指定 `sdk.dir`）。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

APK 輸出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 發布 Release

推一個 `v*` tag 即會觸發 GitHub Actions 建置並發布 APK 到 GitHub Releases：

```bash
git tag v1.0.0
git push origin v1.0.0
```

versionName / versionCode 會自動從 tag 推導（`v1.2.3` → versionName `1.2.3`、versionCode `10203`）。

### 正式簽章（可選）

預設 release APK 用 debug 金鑰簽章。要用正式金鑰，在 repo 設定以下 secrets：

- `RELEASE_KEYSTORE_BASE64`（keystore 檔 base64）
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
