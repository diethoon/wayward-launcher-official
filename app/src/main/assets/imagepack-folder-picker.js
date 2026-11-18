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
          raw = host.listImagePackDirectoryForRequest(args[0], requestId || "");
          break;
        case "readImagePackFile":
          raw = host.readImagePackFileForRequest(args[0], requestId || "");
          break;
        case "createImagePackFile":
          raw = host.createImagePackFile(args[0], args[1], args[2], requestId || "");
          break;
        case "writeImagePackFile":
          raw = host.writeImagePackFile(args[0], args[1], args[2], requestId || "");
          break;
        case "createImagePackDirectory":
          raw = host.createImagePackDirectory(args[0], args[1], requestId || "");
          break;
        case "deleteImagePackEntry":
          raw = host.deleteImagePackEntry(args[0], requestId || "");
          break;
        case "getImagePackFolderInfo":
          raw = host.getImagePackFolderInfo();
          break;
        case "resolveImagePackEntry":
          raw = host.resolveImagePackEntry(args[0], args[1], requestId || "");
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
        return this.queryPermission(options);
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
        if (options && options.keepExistingData === true) {
          const existing = await this.getFile();
          buffer = new Uint8Array(await existing.arrayBuffer());
        }
        let position = 0;
        let closed = false;
        return {
          async write(value) {
            if (closed) throw domError("InvalidStateError", "The writable stream is closed.");
            if (value && typeof value === "object" && value.type === "seek") {
              position = Math.max(0, Number(value.position) || 0);
              return;
            }
            if (value && typeof value === "object" && value.type === "truncate") {
              const size = Math.max(0, Number(value.size) || 0);
              if (size > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
              const resized = new Uint8Array(size);
              resized.set(buffer.subarray(0, size));
              buffer = resized;
              position = Math.min(position, size);
              return;
            }
            if (value && typeof value === "object" && value.type === "write") {
              if (Number.isFinite(value.position)) position = Math.max(0, value.position);
              value = value.data;
            }
            let bytes;
            if (value instanceof Blob) bytes = new Uint8Array(await value.arrayBuffer());
            else if (typeof value === "string") bytes = new TextEncoder().encode(value);
            else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
            else if (ArrayBuffer.isView(value)) {
              bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
            } else throw new TypeError("Unsupported SAF writable data.");
            const required = position + bytes.length;
            if (required > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
            const next = new Uint8Array(Math.max(buffer.length, required));
            next.set(buffer);
            next.set(bytes, position);
            buffer = next;
            position = required;
          },
          async close() {
            if (closed) return;
            let binary = "";
            for (let offset = 0; offset < buffer.length; offset += 0x8000) {
              binary += String.fromCharCode(...buffer.subarray(offset, offset + 0x8000));
            }
            nativeJson("writeImagePackFile", permissions.requestId, uri,
              metadata.mimeType || "application/octet-stream", btoa(binary));
            closed = true;
          },
          async abort() {
            closed = true;
            buffer = new Uint8Array(0);
          }
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