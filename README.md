# 🛡️ PageVault

> **Local-First Web Preservation & On-Device AI Knowledge Vault for Android**

PageVault is an open-source, client-side Android application designed to capture complete, uncompromised snapshots of web articles and research materials. Unlike traditional "read-it-later" utilities that discard layout formatting and dynamic assets, PageVault captures the full Document Object Model (DOM) as an RFC-compliant MHTML archive while simultaneously generating an off-screen, full-height visual screenshot—processed and indexed entirely on your device's local silicon.

---

## ⚡ Key Highlights

* **Dual-Fidelity Archival Engine:**
  * **Interactive MHTML Snapshot:** Serializes live DOM states, inline CSS, and base64-stabilized assets into standalone `.mht` files via a headless Chromium WebView.
  * **Full-Height Visual Canvas:** Renders a 1080p-wide, multi-thousand-pixel off-screen bitmap for long-term graphical reference.
* **Zero-Cloud, On-Device Intelligence:**
  * **LiteRT / MediaPipe Tasks GenAI:** Executes local large language models (such as Gemma 2B or Qwen 1.5B) across device GPU/NPU hardware delegates without remote server calls.
  * **Extractive Heuristic Fallback:** Provides zero-latency deterministic summaries and OpenGraph metadata parsing when running without an active model file.
  * **Google ML Kit Text Recognition:** Extracts unselectable text from diagrams, tables, and infographic images on-device.
* **Granular Inspection & Fluid Viewer:**
  * Interactive dual-mode reader featuring an embedded MHTML renderer and a hardware-accelerated screenshot canvas supporting **1x to 5x pinch-to-zoom**, smooth panning, and double-tap zoom triggers.
* **Multi-Format Export & Sharing:**
  * Converts single or batch-selected archives into multi-page PDFs formatted for standard US Letter prints.
  * Leverages Android's secure `FileProvider` architecture for native Share Sheet delivery directly to Google Drive, local storage, or messaging apps.
* **Structured SQLite & FTS4 Query Engine:**
  * All captures, metadata, OCR text, and summaries are indexed locally using a Room Database with SQLite FTS4 virtual tables for instant offline search queries.
* **Modern Android Ethos UI:**
  * Built entirely with Jetpack Compose and Material 3, incorporating automatic domain color badges, expandable detail cards, and dynamic Day/Night/System theme controls.

---

## 🏛️ System Architecture
[ INCOMING URL / SHARE INTENT ]
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │    WebArchiveCaptureManager   │
                  │  Headless Chromium Execution  │
                  └───────────────┬───────────────┘
                                  │
             ┌────────────────────┴────────────────────┐
             ▼                                         ▼
 [ RFC-Compliant .mht ]                     [ Full-Page Bitmap ]
 DOM & Asset Serialization                  Off-screen Canvas Render
             │                                         │
             │                                         ▼
             │                              ┌─────────────────────┐
             │                              │ ML Kit Text Vision  │
             │                              │ On-Device Visual OCR│
             │                              └──────────┬──────────┘
             │                                         │
             └────────────────────┬────────────────────┘
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │     LocalAiEdgeProcessor      │
                  │ LiteRT (GPU/NPU) or Fallback  │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │   Room SQLite Database (FTS4) │
                  │  Encrypted App-Private Storage│
                  └───────────────────────────────┘
---

## 🛠️ Technology Stack

* **Platform:** Android (Min SDK: 29 / Android 10, Target SDK: 35)
* **Language:** Kotlin 2.0+
* **User Interface:** Jetpack Compose, Material 3, Material Icons Extended
* **Data Storage:** Android Jetpack Room (SQLite + FTS4 Full-Text Search)
* **Image Loading:** Coil Compose
* **On-Device AI / ML:** 
  * Google AI Edge / MediaPipe Tasks GenAI (`com.google.mediapipe:tasks-genai`)
  * Google ML Kit Text Recognition (`com.google.android.gms:play-services-mlkit-text-recognition`)
* **Concurrency:** Kotlin Coroutines & Asynchronous StateFlow

---

## 🔒 Privacy & Local-First Commitment

PageVault is built on strict data privacy principles:
* **Zero External Analytics or Trackers:** The application contains no telemetry or remote analytics SDKs.
* **No Relay Servers:** Web requests originate exclusively from the local device Chromium runtime, identical to standard mobile web navigation.
* **App-Private Storage:** Snapshots, cached assets, and SQLite databases reside within `context.filesDir` and cannot be accessed by other applications without explicit user export actions.

---

## ⚖️ License & Privacy

* **Privacy First:** PageVault operates entirely offline. No browsing history, queries, or captured archives are ever transmitted to external servers.
* **License:** Distributed under the [MIT License](License.md). See the [License.md](License.md) file for full details.               
