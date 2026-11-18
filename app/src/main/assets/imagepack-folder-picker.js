(() => {
  "use strict";
  const host = window.WMLHost;
  const nativeCapability = "__WAYWARD_FOLDER_PICKER_CAPABILITY__";
  if (!host || typeof host.chooseImagePackFolder !== "function" || !nativeCapability) return;
  if (window.__waywardNativeImagePackPickerInstalled &&
      typeof window.showDirectoryPicker === "function") return;

  function diagnose(stage, requestId, details) {
    try {
      if (typeof host.recordImagePackDiagnostic === "function") {
        host.recordImagePackDiagnostic(requestId || "", stage, JSON.stringify(details || {}));
      }
    } catch (_) {}
  }

  let nextId = 0;
  const pending = new Map();
  const rootByHandle = new WeakMap();

  function domError(name, message) {
    try { return new DOMException(message || name, name); }
    catch (_) {
      const error = new Error(message || name);
      error.name = name;
      return error;
    }
  }

  function parseResult(raw) {
    let result;
    try { result = typeof raw === "string" ? JSON.parse(raw) : raw; }
    catch (_) { throw domError("NotReadableError", "The native SAF bridge returned invalid data."); }
    if (!result || result.ok !== true) {
      throw domError(result && result.name || "NotReadableError",
        result && result.message || "The native SAF operation failed.");
    }
    return result;
  }

  function nativeRequest(mode, initialDocumentUri) {
    const id = "saf-" + Date.now().toString(36) + "-" +
      (++nextId) + "-" + Math.floor(Math.random() * 1000000000);
    c¶»§q«^