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
    const startedAt = Date.now();
    const frameOrigin = String(location.origin || "");
    const topFrame = window === window.top;
    let topOrigin = "";
    try { topOrigin = String(window.top.location.origin || ""); } catch (_) {}
    let frameDepth = 0;
    try {
      let parent = window;
      while (parent !== parent.parent) { frameDepth++; parent = parent.parent; }
    } catch (_) {}
    diagnose("show_directory_picker_called", id, {
      origin: frameOrigin, frameOrigin, topOrigin, pagePath: String(location.pathname || ""),
      frameDepth, isTopFrame: topFrame, mode
    });
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        diagnose("show_directory_picker_timeout", id, {
          timedOut: true, durationMs: Date.now() - startedAt, isTopFrame: topFrame
        });
        reject(domError("AbortError", "SAF folder selection timed out."));
      }, 120000);
      pending.set(id, {
        resolve(value) {
          clearTimeout(timeout);
          diagnose("show_directory_picker_promise_resolved", id, {
            durationMs: Date.now() - startedAt, writable: value && value.writable === true
          });
          resolve(value);
        },
        reject(error) {
          clearTimeout(timeout);
          diagnose("show_directory_picker_promise_rejected", id, {
            durationMs: Date.now() - startedAt, errorName: error && error.name || "Error"
          });
          reject(error);
        }
      });
      try {
        diagnose("wmlhost_choose_folder_call_start", id, { bridgeAvailable: true, isTopFrame: topFrame });
        host.chooseImagePackFolder(id, nativeCapability, mode, initialDocumentUri || "");
        diagnose("wmlhost_choose_folder_call_returned", id, { durationMs: Date.now() - startedAt });
      } catch (error) {
        pending.delete(id);
        clearTimeout(timeout);
        diagnose("wmlhost_choose_folder_call_failed", id, {
          errorName: error && error.name || "Error",
          errorMessage: String(error && error.message || error)
        });
        reject(domError("NotAllowedError", String(error && error.message || error)));
      }
    });
  }

  Object.defineProperty(window, "__waywardNativeImagePackPickerResolve", {
    configurable: false,
    enumerable: false,
    value(id, result) {
      const request = pending.get(String(id));
      if (!request) return false;
      pending.delete(String(id));
      if (result && result.ok === true) {
        result.requestId = String(id);
        request.resolve(result);
      } else request.reject(domError(result && result.name || "AbortError",
        result && result.message || "SAF folder selection was cancelled."));
      return true;
    }
  });

  Object.defineProperty(window, "__waywardNativeImagePackPickerDispatch", {
    configurable: false,
    enumerable: false,
    value(id, result) {
      let delivered = 0;
      let visited = 0;
      const visit = frame => {
        let frameOrigin;
        try { frameOrigin = frame.location.origin; } catch (_) { return; }
        if (frameOrigin !== location.origin) return;
        visited++;
        try {
          if (typeof frame.__waywardNativeImagePackPickerResolve === "function" &&
              frame.__waywardNativeImagePackPickerResolve(id, result)) delivered++;
        } catch (_) {}
        try {
          for (let index = 0; index < frame.frames.length; index++) visit(frame.frames[index]);
        } catch (_) {}
      };
      visit(window);
      diagnose("native_result_frame_dispatch", id, {
        dispatchCount: delivered, sameOriginFrameCount: visited
      });
      return delivered;
    }
  });

  function childName(value) {
    const name = String(value || "");
    if (!name || name === "." || name === ".." || name.indexOf("/") >= 0 ||
        name.indexOf("\\") >= 0 || name.indexOf("\0") >= 0) {
      throw domError("TypeError", "A single valid file or directory name is required.");
    }
    return name;
  }

  function handleUri(handle) {
    const uri = handle && handle.__waywardSafDocumentUri;
    if (typeof uri !== "string" || !uri.startsWith("content://")) {
      throw domError("NotFoundError", "This handle has no valid SAF document URI.");
    }
    return uri;
  }

  function nativeJson(method, requestId, ...args) {
    try {
      let raw;
      switch (method) {
        case "listImagePackDirectory":
          raw = host.listImagePackDirectoryForRequest(nativeCapability, args[0], requestId || "");
          break;
        case "readImagePackFile":
          raw = host.readImagePackFileForRequest(nativeCapability, args[0], requestId || "");
          break;
        case "createImagePackFile":
          raw = host.createImagePackFile(nativeCapability, args[0], args[1], args[2], requestId || "");
          break;
        case "writeImagePackFile":
          raw = host.writeImagePackFile(nativeCapability, args[0], args[1], args[2], requestId || "");
          break;
        case "createImagePackDirectory":
          raw = host.createImagePackDirectory(nativeCapability, args[0], args[1], requestId || "");
          break;
        case "deleteImagePackEntry":
          raw = host.deleteImagePackEntry(nativeCapability, args[0], requestId || "");
          break;
        case "getImagePackFolderInfo":
          raw = host.getImagePackFolderInfo(nativeCapability);
          break;
        case "resolveImagePackEntry":
          raw = host.resolveImagePackEntry(nativeCapability, args[0], args[1], requestId || "");
          break;
        default:
          throw domError("NotSupportedError", "The native SAF operation is unavailable.");
      }
      const result = parseResult(raw);
      diagnose("native_" + method + "_success", requestId, {
        method, uriBased: true,
        entryCount: result.entries ? result.entries.length : undefined,
        fileSize: typeof result.size === "number" ? result.size : undefined
      });
      return result;
    } catch (error) {
      diagnose("native_" + method + "_failure", requestId, {
        method, uriBased: true, errorName: error && error.name || "Error",
        errorMessage: String(error && error.message || error)
      });
      throw error;
    }
  }

  function listEntries(directoryUri, requestId) {
    diagnose("directory_entries_native_call", requestId, { method: "listImagePackDirectory", uriBased: true });
    const entries = nativeJson("listImagePackDirectory", requestId, directoryUri).entries || [];
    diagnose("directory_entries_returned", requestId, { entryCount: entries.length });
    return entries;
  }

  // FAITH 3.0.0 uses <input type="file" webkitdirectory multiple>.
  // This is deliberately a FileList adapter, not a DirectoryHandle alias.
  const MAX_UPLOAD_FILES = 50000;
  const MAX_UPLOAD_DEPTH = 128;
  let uploadPickerBusy = false;

  function safePathSegment(value) {
    const name = String(value || "");
    if (!name || name === "." || name === ".." || /[\/\\\0]/.test(name)) {
      throw domError("NotReadableError", "The selected SAF provider returned an invalid path segment.");
    }
    return name;
  }

  function mimeType(value) {
    const type = String(value || "").trim().toLowerCase();
    return /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(type) ? type : "";
  }

  function makeSafBackedFile(entry, relativePath, requestId) {
    const metadata = {
      name: safePathSegment(entry.name),
      size: Number.isFinite(Number(entry.size)) && Number(entry.size) >= 0
        ? Number(entry.size) : null,
      type: mimeType(entry.mimeType),
      lastModified: Number.isFinite(Number(entry.lastModified)) && Number(entry.lastModified) >= 0
        ? Number(entry.lastModified) : 0,
      webkitRelativePath: relativePath
    };
    const documentUri = String(entry.uri || "");
    if (!documentUri.startsWith("content://")) {
      throw domError("SecurityError", "The SAF provider returned a non-content file URI.");
    }

    let inFlight = null;
    const loadBytes = () => {
      if (inFlight) return inFlight;
      inFlight = Promise.resolve().then(() => {
        const payload = nativeJson("readImagePackFile", requestId, documentUri);
        const encoded = String(payload.data || "");
        let binary;
        try { binary = atob(encoded); }
        catch (_) { throw domError("NotReadableError", "The SAF file contents were not valid Base64."); }
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        if (metadata.size === null) metadata.size = bytes.length;
        return bytes;
      }).finally(() => { inFlight = null; });
      return inFlight;
    };

    // Keep Blob/File identity and synchronous slice() semantics while deferring
    // the SAF read until a consumer actually asks for bytes.
    class SafBackedFile extends Blob {
      constructor(start, end, contentType) {
        super([], { type: contentType === undefined ? metadata.type : contentType });
        this._start = start;
        this._end = end;
        Object.defineProperties(this, {
          name: { value: metadata.name, enumerable: true },
          size: { value: Math.max(0, end - start), enumerable: true },
          lastModified: { value: metadata.lastModified, enumerable: true },
          size: { get: () => Math.max(0, (end === null ? (metadata.size === null ? 0 : metadata.size) : end) - start), enumerable: true },
          webkitRelativePath: { value: metadata.webkitRelativePath, enumerable: true }
        });
      }
      async arrayBuffer() {
        const bytes = await loadBytes();
        const end = this._end === null ? bytes.length : this._end;
        return bytes.slice(this._start, end).buffer;
      }
      async text() {
        return new TextDecoder().decode(await this.arrayBuffer());
      }
      async bytes() {
        return new Uint8Array(await this.arrayBuffer());
      }
      stream() {
        const file = this;
        let pendingBytes = null;
        let offset = 0;
        return new ReadableStream({
          async pull(controller) {
            try {
              if (pendingBytes === null) pendingBytes = await file.bytes();
              if (offset >= pendingBytes.length) {
                controller.close();
                pendingBytes = null;
                return;
              }
              const end = Math.min(offset + 65536, pendingBytes.length);
              controller.enqueue(pendingBytes.slice(offset, end));
              offset = end;
            } catch (error) {
              controller.error(error);
            }
          }
        });
      }
      slice(start, end, contentType) {
        const size = this.size;
        const relativeStart = start === undefined ? 0 : Number(start);
        const relativeEnd = end === undefined ? size : Number(end);
        const from = relativeStart < 0
          ? Math.max(size + relativeStart, 0) : Math.min(relativeStart, size);
        const to = relativeEnd < 0
          ? Math.max(size + relativeEnd, 0) : Math.min(relativeEnd, size);
        const absoluteStart = this._start + Math.trunc(Number.isFinite(from) ? from : 0);
        const absoluteEnd = end === undefined && this._end === null
          ? null
          : this._start + Math.trunc(Math.max(from, Number.isFinite(to) ? to : size));
        return new SafBackedFile(absoluteStart, absoluteEnd,
          contentType === undefined ? "" : String(contentType));
      }
    }
    // File.prototype supplies the File brand; Blob's internal slot is created by super().
    Object.setPrototypeOf(SafBackedFile.prototype, File.prototype);
    return new SafBackedFile(0, metadata.size, metadata.type);
  }

  async function enumerateSafTree(rootUri, rootName, requestId) {
    const rootSegment = safePathSegment(rootName);
    const visited = new Set();
    const visitedFileUris = new Set();
    const files = [];
    let entryCount = 0;

    async function visit(directoryUri, prefix, depth) {
      if (depth > MAX_UPLOAD_DEPTH) {
        throw domError("QuotaExceededError", "The selected folder is nested too deeply.");
      }
      if (visited.has(directoryUri)) {
        throw domError("NotReadableError", "The SAF provider returned a repeated directory URI.");
      }
      visited.add(directoryUri);
      const children = listEntries(directoryUri, requestId).slice().sort((left, right) =>
        String(left.name || "").localeCompare(String(right.name || "")));
      for (const child of children) {
        entryCount++;
        if (entryCount > MAX_UPLOAD_FILES * 2) {
          throw domError("QuotaExceededError", "The selected folder contains too many entries.");
        }
        const name = safePathSegment(child.name);
        const relativePath = prefix + "/" + name;
        if (child.kind === "directory") {
          if (!child.uri || !String(child.uri).startsWith("content://")) {
            throw domError("SecurityError", "The SAF provider returned an invalid directory URI.");
          }
          await visit(String(child.uri), relativePath, depth + 1);
        } else if (child.kind === "file") {
          if (files.length >= MAX_UPLOAD_FILES) {
            throw domError("QuotaExceededError", "The selected folder contains too many files.");
          }
          const fileUri = String(child.uri || "");
          if (!fileUri.startsWith("content://")) {
            throw domError("SecurityError", "The SAF provider returned an invalid file URI.");
          }
          if (visitedFileUris.has(fileUri)) {
            throw domError("NotReadableError", "The SAF provider returned a repeated file URI.");
          }
          visitedFileUris.add(fileUri);
          files.push(makeSafBackedFile(child, relativePath, requestId));
        } else {
          throw domError("NotReadableError", "The SAF provider returned an unknown entry kind.");
        }
      }
    }

    await visit(rootUri, rootSegment, 0);
    return files;
  }

  function makeFileList(files) {
    const prototype = typeof FileList === "function" ? FileList.prototype : Object.prototype;
    const list = Object.create(prototype);
    Object.defineProperty(list, "length", {
      configurable: false, enumerable: true, get: () => files.length
    });
    Object.defineProperty(list, "item", {
      configurable: false, enumerable: false,
      value(index) {
        const position = Number(index);
        return Number.isInteger(position) && position >= 0 && position < files.length
          ? files[position] : null;
      }
    });
    Object.defineProperty(list, Symbol.iterator, {
      configurable: false, enumerable: false,
      value: function* () { yield* files; }
    });
    Object.defineProperty(list, Symbol.toStringTag, { value: "FileList" });
    files.forEach((file, index) => {
      Object.defineProperty(list, index, {
        configurable: false, enumerable: true, value: file
      });
    });
    return list;
  }

  function isDirectoryUploadInput(input) {
    if (!input || String(input.type || "").toLowerCase() !== "file") return false;
    try {
      return input.webkitdirectory === true ||
        (typeof input.hasAttribute === "function" && input.hasAttribute("webkitdirectory"));
    } catch (_) {
      return false;
    }
  }

  function findDirectoryUploadInput(event) {
    const path = event && typeof event.composedPath === "function"
      ? event.composedPath() : [event && event.target];
    for (const candidate of path) {
      if (!candidate || candidate.nodeType !== 1) continue;
      if (isDirectoryUploadInput(candidate)) return candidate;
      if (String(candidate.tagName || "").toUpperCase() === "LABEL") {
        try {
          if (isDirectoryUploadInput(candidate.control)) return candidate.control;
          const nested = candidate.querySelector &&
            candidate.querySelector('input[type="file"][webkitdirectory]');
          if (isDirectoryUploadInput(nested)) return nested;
        } catch (_) {}
      }
    }
    return null;
  }

  function dispatchInputEvent(input, name, bubbles) {
    const view = input.ownerDocument && input.ownerDocument.defaultView;
    const EventType = view && view.Event || Event;
    input.dispatchEvent(new EventType(name, { bubbles: !!bubbles, composed: true }));
  }

  // Retain the successful selection after dispatch; FAITH scans asynchronously.
  const activeFileLists = new WeakMap();

  function dispatchDirectoryFileList(input, files) {
    const fileList = makeFileList(files);
    const previous = activeFileLists.get(input);
    const previousDescriptor = Object.getOwnPropertyDescriptor(input, "files");
    try {
      Object.defineProperty(input, "files", { configurable: true, enumerable: true, get: () => fileList });
      activeFileLists.set(input, fileList);
      // Match a successful native file selection: input first, then change.
      dispatchInputEvent(input, "input", true);
      dispatchInputEvent(input, "change", true);
    } catch (error) {
      if (previous) {
        Object.defineProperty(input, "files", { configurable: true, enumerable: true, get: () => previous });
        activeFileLists.set(input, previous);
      } else {
        if (previousDescriptor) Object.defineProperty(input, "files", previousDescriptor); else delete input.files;
        activeFileLists.delete(input);
      }
      throw error;
    }
  }

  function dispatchDirectoryCancel(input) {
    try { dispatchInputEvent(input, "cancel", false); } catch (_) {}
  }

  async function chooseDirectoryForFileInput(input) {
    if (uploadPickerBusy) {
      diagnose("webkitdirectory_upload_rejected_busy", "", { result: "invalid_state" });
      return;
    }
    uploadPickerBusy = true;
    let requestId = "";
    try {
      diagnose("webkitdirectory_upload_started", "", {
        multiple: input.multiple === true,
        frameOrigin: String(location.origin || ""),
        isTopFrame: window === window.top
      });
      const selection = await nativeRequest("read", "");
      requestId = String(selection.requestId || "");
      if (!selection.documentUri || !selection.uri) {
        throw domError("NotReadableError", "Android did not return a SAF tree and root document URI.");
      }
      const files = await enumerateSafTree(
        String(selection.documentUri), String(selection.name || "Image packs"), requestId);
      if (!files.length) {
        throw domError("NotFoundError", "The selected folder contains no files.");
      }
      dispatchDirectoryFileList(input, files);
      diagnose("webkitdirectory_upload_dispatched", requestId, {
        fileCount: files.length, rootName: String(selection.name || ""),
        multiple: input.multiple === true
      });
    } catch (error) {
      const name = error && error.name || "NotReadableError";
      const message = String(error && error.message || error).slice(0, 240);
      diagnose("webkitdirectory_upload_failed", requestId, { errorName: name, message });
      if (name === "AbortError") dispatchDirectoryCancel(input);
      try {
        const view = input.ownerDocument && input.ownerDocument.defaultView || window;
        view.dispatchEvent(new view.CustomEvent("waywardfoldererror", {
          detail: { name, message }, bubbles: false
        }));
      } catch (_) {}
    } finally {
      uploadPickerBusy = false;
    }
  }

  function installWebkitDirectoryAdapter() {
    if (!window.document || window.__waywardWebkitDirectoryAdapterInstalled) return;
    const onClick = event => {
      const input = findDirectoryUploadInput(event);
      if (!input || input.disabled) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      chooseDirectoryForFileInput(input);
    };
    document.addEventListener("click", onClick, true);

    // Some applications invoke showPicker() rather than click() or a label.
    try {
      const prototype = window.HTMLInputElement && window.HTMLInputElement.prototype;
      const original = prototype && prototype.showPicker;
      if (prototype && !prototype.__waywardDirectoryShowPickerPatched) {
        Object.defineProperty(prototype, "__waywardDirectoryShowPickerPatched", { value: true });
        Object.defineProperty(prototype, "showPicker", {
          configurable: true, writable: true,
          value: function() {
            if (isDirectoryUploadInput(this)) {
              if (!this.disabled) chooseDirectoryForFileInput(this);
              return;
            }
            if (typeof original === "function") return original.call(this);
            throw domError("NotSupportedError", "The native input picker is unavailable.");
          }
        });
      }
    } catch (error) {
      diagnose("webkitdirectory_show_picker_patch_unavailable", "", {
        errorName: error && error.name || "Error"
      });
    }

    window.__waywardWebkitDirectoryAdapterInstalled = true;
    diagnose("webkitdirectory_adapter_installed", "", {
      installed: true, documentStart: document.readyState === "loading",
      sameOriginFrame: true
    });
  }

  function queryHandlePermission(permissions, options) {
    const mode = options && options.mode !== undefined ? options.mode : "read";
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid permission mode.");
    try {
      const info = nativeJson("getImagePackFolderInfo", permissions.requestId || "");
      if (info.uri !== permissions.treeUri) return "denied";
      return mode === "readwrite" && info.writable !== true ? "denied" : "granted";
    } catch (_) {
      return "denied";
    }
  }

  function makeDirectory(uri, name, permissions) {
    const handle = {
      kind: "directory",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "directory" &&
          rootByHandle.get(other) === rootByHandle.get(handle) &&
          other.__waywardSafDocumentUri === uri;
      },
      async queryPermission(options) {
        return queryHandlePermission(permissions, options);
      },
      async requestPermission(options) {
        const mode = options && options.mode !== undefined ? options.mode : "read";
        if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid permission mode.");
        const current = await this.queryPermission({ mode });
        if (current === "granted") return "granted";
        try {
          const selection = await nativeRequest(mode, uri);
          if (selection.uri !== permissions.treeUri) return "denied";
          if (mode === "readwrite" && selection.writable !== true) return "denied";
          if (mode === "readwrite") permissions.writable = true;
          return await this.queryPermission({ mode });
        } catch (error) { if (error && error.name === "AbortError") return "denied"; throw error; }
      },
      async getDirectoryHandle(child, options) {
        diagnose("directory_handle_get_directory_started", permissions.requestId, { method: "getDirectoryHandle" });
        child = childName(child);
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "Directory not found: " + child);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
          const created = nativeJson("createImagePackDirectory", permissions.requestId, uri, child);
          return makeDirectory(created.uri, child, permissions);
        }
        if (entry.kind !== "directory") throw domError("TypeMismatchError", "The selected entry is not a directory.");
        return makeDirectory(entry.uri, child, permissions);
      },
      async getFileHandle(child, options) {
        diagnose("directory_handle_get_file_started", permissions.requestId, { method: "getFileHandle" });
        child = childName(child);
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "File not found: " + child);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
          const created = nativeJson("createImagePackFile", permissions.requestId,
            uri, child, "application/octet-stream");
          return makeFile(created.uri, child, { mimeType: "application/octet-stream" }, permissions);
        }
        if (entry.kind !== "file") throw domError("TypeMismatchError", "The selected entry is not a file.");
        return makeFile(entry.uri, child, entry, permissions);
      },
      async removeEntry(child, options) {
        child = childName(child);
        if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
        const entry = listEntries(uri, permissions.requestId).find(item => item.name === child);
        if (!entry) throw domError("NotFoundError", "Entry not found: " + child);
        if (entry.kind === "directory" && (!options || options.recursive !== true) &&
            listEntries(entry.uri, permissions.requestId).length > 0) {
          throw domError("InvalidModificationError", "A non-empty directory requires recursive removal.");
        }
        nativeJson("deleteImagePackEntry", permissions.requestId, entry.uri);
      },
      async *entries() {
        diagnose("directory_handle_entries_started", permissions.requestId, { uriBased: true });
        try {
          const children = listEntries(uri, permissions.requestId);
          diagnose("directory_handle_entries_succeeded", permissions.requestId, { entryCount: children.length });
          for (const entry of children) {
            const child = entry.kind === "directory"
              ? makeDirectory(entry.uri, entry.name, permissions)
              : makeFile(entry.uri, entry.name, entry, permissions);
            yield [entry.name, child];
          }
        } catch (error) {
          diagnose("directory_handle_entries_failed", permissions.requestId, {
            errorName: error && error.name || "Error"
          });
          throw error;
        }
      },
      [Symbol.asyncIterator]() {
        return this.entries();
      },
      async *values() {
        for await (const pair of this.entries()) yield pair[1];
      },
      async *keys() {
        for await (const pair of this.entries()) yield pair[0];
      },
      async resolve(other) {
        if (!other || rootByHandle.get(other) !== rootByHandle.get(handle)) return null;
        const result = nativeJson("resolveImagePackEntry", permissions.requestId,
          uri, handleUri(other));
        return Array.isArray(result.path) ? result.path : null;
      }
    };
    Object.defineProperty(handle, "__waywardSafDocumentUri", { value: uri });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  function makeFile(uri, name, metadata, permissions) {
    const handle = {
      kind: "file",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "file" &&
          rootByHandle.get(other) === rootByHandle.get(handle) &&
          other.__waywardSafDocumentUri === uri;
      },
      async queryPermission(options) {
        return queryHandlePermission(permissions, options);
      },
      async requestPermission(options) {
        return this.queryPermission(options);
      },
      async getFile() {
        diagnose("file_handle_get_file_started", permissions.requestId, { uriBased: true });
        const payload = nativeJson("readImagePackFile", permissions.requestId, uri);
        const binary = atob(payload.data || "");
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        const file = new File([bytes], name, {
          type: payload.mimeType || metadata.mimeType || "application/octet-stream",
          lastModified: Number(payload.lastModified || metadata.lastModified || 0)
        });
        diagnose("file_handle_get_file_succeeded", permissions.requestId, { fileSize: payload.size || 0 });
        return file;
      },
      async createWritable(options) {
        if (!permissions.writable) throw domError("NotAllowedError", "The selected SAF tree is read-only.");
        let buffer = new Uint8Array(0);
        if (options && options.keepExistingData === true) { const existing = await this.getFile(); buffer = new Uint8Array(await existing.arrayBuffer()); }
        let position = 0, closed = false, aborted = false;
        const ensureOpen = () => { if (closed || aborted) throw domError("InvalidStateError", "The writable stream is no longer open."); };
        const seek = async value => { ensureOpen(); const next = Number(value); if (!Number.isFinite(next) || next < 0) throw new TypeError("The seek position must be a non-negative finite number."); position = Math.floor(next); };
        const truncate = async value => {
          ensureOpen(); const size = Number(value);
          if (!Number.isFinite(size) || size < 0) throw new TypeError("The truncate size must be a non-negative finite number.");
          const length = Math.floor(size); if (length > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
          const resized = new Uint8Array(length); resized.set(buffer.subarray(0, length)); buffer = resized; position = Math.min(position, length);
        };
        const write = async value => {
          ensureOpen();
          if (value && typeof value === "object" && value.type === "seek") return seek(value.position);
          if (value && typeof value === "object" && value.type === "truncate") return truncate(value.size);
          if (value && typeof value === "object" && value.type === "write") { if (value.position !== undefined) await seek(value.position); value = value.data; }
          let bytes;
          if (value instanceof Blob) bytes = new Uint8Array(await value.arrayBuffer());
          else if (typeof value === "string") bytes = new TextEncoder().encode(value);
          else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
          else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
          else throw new TypeError("Unsupported SAF writable data.");
          const required = position + bytes.length; if (required > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
          const next = new Uint8Array(Math.max(buffer.length, required)); next.set(buffer); next.set(bytes, position); buffer = next; position = required;
        };
        return { write, seek, truncate,
          async close() {
            ensureOpen(); let binary = "";
            for (let offset = 0; offset < buffer.length; offset += 0x8000) binary += String.fromCharCode(...buffer.subarray(offset, offset + 0x8000));
            nativeJson("writeImagePackFile", permissions.requestId, uri, metadata.mimeType || "application/octet-stream", btoa(binary));
            closed = true;
          },
          async abort() { if (closed || aborted) return; aborted = true; buffer = new Uint8Array(0); }
        };
      }
    };
    Object.defineProperty(handle, "__waywardSafDocumentUri", { value: uri });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  const showDirectoryPicker = async function(options) {
    options = options || {};
    const mode = options.mode === undefined ? "readwrite" : options.mode;
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid directory picker mode.");
    if (options.id !== undefined && options.id !== null && String(options.id).length > 0) {
      throw domError("NotSupportedError", "Android SAF cannot preserve the File System Access picker id.");
    }
    let initialDocumentUri = "";
    if (options.startIn !== undefined && options.startIn !== null) {
      if (typeof options.startIn === "string" || !options.startIn.__waywardSafDocumentUri ||
          options.startIn.kind !== "directory") {
        throw domError("NotSupportedError", "Android SAF can only honor startIn from a previously selected SAF directory handle.");
      }
      initialDocumentUri = handleUri(options.startIn);
    }
    const selection = await nativeRequest(mode, initialDocumentUri);
    if (mode === "readwrite" && selection.writable !== true) {
      throw domError("NotAllowedError", "Android did not grant write access to the selected folder.");
    }
    if (!selection.documentUri || !selection.uri) {
      throw domError("NotReadableError", "Android did not return the selected SAF tree and root document URIs.");
    }
    const permissions = {
      root: selection.uri, treeUri: selection.uri,
      writable: selection.writable === true, requestId: selection.requestId || ""
    };
    diagnose("directory_handle_created", permissions.requestId, {
      directoryHandle: true, handleKind: "directory", writable: permissions.writable,
      documentUriPresent: true
    });
    return makeDirectory(selection.documentUri, selection.name || "Image packs", permissions);
  };

  try {
    Object.defineProperty(window, "showDirectoryPicker", {
      configurable: true, enumerable: true, writable: true, value: showDirectoryPicker
    });
  } catch (error) {
    try { window.showDirectoryPicker = showDirectoryPicker; }
    catch (assignmentError) {
      diagnose("show_directory_picker_registration_failed", "", {
        errorName: assignmentError && assignmentError.name || "Error"
      });
      return;
    }
  }

  if (typeof window.showDirectoryPicker !== "function") {
    diagnose("show_directory_picker_registration_failed", "", { result: "missing_function" });
    return;
  }
  window.__waywardNativeImagePackPickerInstalled = true;
  installWebkitDirectoryAdapter();
  diagnose("imagepack_adapter_installed", "", {
    installed: true, bridgeAvailable: true, origin: String(location.origin || ""),
    isTopFrame: window === window.top, storageIdentity: "content-uri"
  });

  window.__waywardReadPersistedImagePackRoot = function() {
    const info = nativeJson("getImagePackFolderInfo", "");
    if (!info.documentUri || !info.uri) throw domError("NotFoundError", "No persisted SAF root is available.");
    const permissions = {
      root: info.uri, treeUri: info.uri, writable: info.writable === true, requestId: ""
    };
    return makeDirectory(info.documentUri, info.name || "Image packs", permissions);
  };
})();