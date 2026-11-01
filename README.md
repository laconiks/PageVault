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
