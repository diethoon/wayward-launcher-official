(() => {
  "use strict";
  if (window.__waywardBlobDownloadBridgeInstalled) return;
  const host = window.WMLHost;
  if (!host || typeof host.beginBlobDownload !== "function" ||
      typeof host.appendBlobDownloadChunk !== "function" ||
      typeof host.finishBlobDownload !== "function") return;
  let sequence = 0;
  const blobByUrl = new Map();
  let deferNextRemovalId = null;
  let deferredRemoval = null;
  const originalRemoveItem = Storage.prototype.removeItem;

  try {
    const createObjectURL = URL.createObjectURL.bind(URL);
    const revokeObjectURL = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = function(blob) {
      const url = createObjectURL(blob);
      if (blob instanceof Blob) blobByUrl.set(url, blob);
      return url;
    };
    URL.revokeObjectURL = function(url) {
      blobByUrl.delete(String(url));
      return revokeObjectURL(url);
    };
  } catch (_) {}

  Storage.prototype.removeItem = function(key) {
    if (deferNextRemovalId && this === window.localStorage) {
      deferredRemoval = { id: deferNextRemovalId, storage: this, key: String(key) };
      deferNextRemovalId = null;
      return;
    }
    return originalRemoveItem.call(this, key);
  };

  window.__waywardBlobDownloadResult = function(requestId, saved) {
    if (!deferredRemoval || deferredRemoval.id !== String(requestId)) return;
    const deferred = deferredRemoval;
    deferredRemoval = null;
    if (saved === true) originalRemoveItem.call(deferred.storage, deferred.key);
    diagnose("blob_download_deferred_log_cleanup", requestId, {
      saved: saved === true, storageKeyDeferred: true
    });
  };

  function diagnose(stage, requestId, details) {
    try {
      if (typeof host.recordImagePackDiagnostic === "function") {
        host.recordImagePackDiagnostic(requestId || "", stage, JSON.stringify(details || {}));
      }
    } catch (_) {}
  }

  function base64(bytes) {
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    return btoa(binary);
  }

  function fallbackMime(filename) {
    const lower = String(filename || "").toLowerCase();
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".jsonl")) return "application/jsonl";
    return "application/octet-stream";
  }

  document.addEventListener("click", async event => {
    const target = event.target;
    const anchor = target && typeof target.closest === "function"
      ? target.closest("a[download]") : null;
    if (!anchor) return;
    const href = String(anchor.href || "");
    if (!href.startsWith("blob:")) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    const requestId = "blob-download-" + Date.now().toString(36) + "-" + (++sequence);
    const filename = String(anchor.download || anchor.getAttribute("download") || "download");
    const isPlayLogExport = filename.startsWith("play-log-");
    if (isPlayLogExport) {
      deferNextRemovalId = requestId;
      Promise.resolve().then(() => {
        if (deferNextRemovalId === requestId) deferNextRemovalId = null;
      });
    }
    const retainedBlob = blobByUrl.get(href) || null;
    diagnose("blob_download_click_intercepted", requestId, {
      filename, hrefScheme: "blob", isPlayLogExport, retainedBlob: !!retainedBlob
    });
    try {
      let blob = retainedBlob;
      if (!blob) {
        const response = await fetch(href);
        if (!response.ok) throw new Error("Blob download data could not be read.");
        blob = await response.blob();
      }
      const mimeType = blob.type || fallbackMime(filename);
      const accepted = host.beginBlobDownload(requestId, filename, mimeType, blob.size);
      if (accepted !== true) throw new Error("Native save request was rejected.");
      const chunkSize = 256 * 1024;
      for (let offset = 0; offset < blob.size; offset += chunkSize) {
        const bytes = new Uint8Array(await blob.slice(offset, offset + chunkSize).arrayBuffer());
        if (host.appendBlobDownloadChunk(requestId, base64(bytes)) !== true) {
          throw new Error("Native save stream rejected a data chunk.");
        }
      }
      if (host.finishBlobDownload(requestId) !== true) {
        throw new Error("Native save stream could not be finalized.");
      }
      diagnose("blob_download_native_request_sent", requestId, {
        filename, mimeType, byteLength: blob.size, chunkSize
      });
    } catch (error) {
      try { host.abortBlobDownload(requestId); } catch (_) {}
      window.__waywardBlobDownloadResult(requestId, false);
      diagnose("blob_download_bridge_failed", requestId, {
        filename, errorName: error && error.name || "Error",
        errorMessage: String(error && error.message || error)
      });
      try {
        window.dispatchEvent(new CustomEvent("wayward:download-error", {
          detail: { filename, message: String(error && error.message || error) }
        }));
      } catch (_) {}
    }
  }, true);

  window.__waywardBlobDownloadBridgeInstalled = true;
  diagnose("blob_download_bridge_installed", "", { installed: true });
})();