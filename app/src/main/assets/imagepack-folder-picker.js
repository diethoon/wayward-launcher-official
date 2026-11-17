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
    catch (_) { throw domError("NotReadableError", "The native image-pack bridge returned invalid data."); }
    if (!result || result.ok !== true) {
      throw domError(result && result.name || "NotReadableError",
        result && result.message || "The image-pack operation failed.");
    }
    return result;
  }

  function nativeRequest(mode) {
    const id = "imagepack-" + Date.now().toString(36) + "-" +
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
      origin: frameOrigin, frameOrigin, topOrigin, pagePath: String(location.pathname || ""), frameDepth,
      isTopFrame: topFrame, mode: mode || "readwrite"
    });
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        diagnose("show_directory_picker_timeout", id, {
          timedOut: true, durationMs: Date.now() - startedAt, isTopFrame: topFrame
        });
        reject(domError("AbortError", "Image-pack folder selection timed out."));
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
        diagnose("wmlhost_choose_folder_call_start", id, {
          bridgeAvailable: typeof host.chooseImagePackFolder === "function",
          isTopFrame: topFrame
        });
        host.chooseImagePackFolder(id, nativeCapability);
        diagnose("wmlhost_choose_folder_call_returned", id, { durationMs: Date.now() - startedAt });
      } catch (error) {
        pending.delete(id);
        clearTimeout(timeout);
        diagnose("wmlhost_choose_folder_call_failed", id, {
          errorName: error && error.name || "Error",
          errorMessage: String(error && error.message || error),
          stack: String(error && error.stack || "")
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
        result && result.message || "Image-pack folder selection was cancelled."));
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
      throw domError("TypeError", "A single relative file or folder name is required.");
    }
    return name;
  }

  function nativeJson(method, requestId, ...args) {
    try {
      let raw;
      if (method === "listImagePackDirectory" || method === "readImagePackFile") {
        const requestMethod = method === "listImagePackDirectory"
          ? "listImagePackDirectoryForRequest" : "readImagePackFileForRequest";
        if (typeof host[requestMethod] === "function") raw = host[requestMethod](args[0], requestId || "");
        else raw = host[method](...args);
      } else {
        if (typeof host[method] !== "function") {
          throw domError("NotSupportedError", "The native image-pack operation is unavailable.");
        }
        raw = host[method](...args);
      }
      const result = parseResult(raw);
      diagnose("native_" + method + "_success", requestId, {
        method, ok: true, entryCount: result.entries ? result.entries.length : undefined,
        fileSize: typeof result.size === "number" ? result.size : undefined
      });
      return result;
    } catch (error) {
      diagnose("native_" + method + "_failure", requestId, {
        method, errorName: error && error.name || "Error",
        errorMessage: String(error && error.message || error),
        stack: String(error && error.stack || "")
      });
      throw error;
    }
  }

  function listEntries(path, requestId) {
    diagnose("directory_entries_native_call", requestId, { method: "listImagePackDirectory" });
    const entries = nativeJson("listImagePackDirectory", requestId, path).entries || [];
    diagnose("directory_entries_returned", requestId, { entryCount: entries.length });
    return entries;
  }

  function makeDirectory(path, name, permissions) {
    const handle = {
      kind: "directory",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "directory" &&
          rootByHandle.get(other) === rootByHandle.get(handle) && other.__path === path;
      },
      async queryPermission(options) {
        return (!options || options.mode !== "readwrite" || permissions.writable)
          ? "granted" : "denied";
      },
      async requestPermission(options) {
        return this.queryPermission(options);
      },
      async getDirectoryHandle(name, options) {
        diagnose("directory_handle_get_directory_started", permissions.requestId, { method: "getDirectoryHandle" });
        name = childName(name);
        const childPath = path ? path + "/" + name : name;
        const entry = listEntries(path, permissions.requestId).find(item => item.name === name);
        if (!entry) {
          if (!options || options.create !== true) {
            throw domError("NotFoundError", "Directory not found: " + name);
          }
          if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
          nativeJson("createImagePackDirectory", permissions.requestId, childPath);
          return makeDirectory(childPath, name, permissions);
        }
        if (entry.kind !== "directory") throw domError("TypeMismatchError", "The selected entry is not a directory.");
        return makeDirectory(childPath, name, permissions);
      },
      async getFileHandle(name, options) {
        diagnose("directory_handle_get_file_handle_started", permissions.requestId, { method: "getFileHandle" });
        name = childName(name);
        const childPath = path ? path + "/" + name : name;
        const entry = listEntries(path, permissions.requestId).find(item => item.name === name);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "File not found: " + name);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
          nativeJson("writeImagePackFile", permissions.requestId, childPath, "application/octet-stream", "");
        } else if (entry.kind !== "file") {
          throw domError("TypeMismatchError", "The selected entry is not a file.");
        }
        diagnose("directory_handle_get_file_handle_succeeded", permissions.requestId, {
          method: "getFileHandle", result: entry ? "existing_file" : "created_file"
        });
        return makeFile(childPath, name, entry || {}, permissions);
      },
      async removeEntry(name) {
        name = childName(name);
        if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
        nativeJson("deleteImagePackEntry", permissions.requestId, path ? path + "/" + name : name);
      },
      async *entries() {
        diagnose("directory_handle_entries_started", permissions.requestId, {});
        try {
          const children = listEntries(path, permissions.requestId);
          diagnose("directory_handle_entries_succeeded", permissions.requestId, {
            entryCount: children.length
          });
          for (const entry of children) {
            const childPath = path ? path + "/" + entry.name : entry.name;
            const child = entry.kind === "directory"
              ? makeDirectory(childPath, entry.name, permissions)
              : makeFile(childPath, entry.name, entry, permissions);
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
        const target = String(other.__path || "");
        if (target === path) return [];
        const prefix = path ? path + "/" : "";
        if (!target.startsWith(prefix)) return null;
        return target.slice(prefix.length).split("/");
      }
    };
    Object.defineProperty(handle, "__path", { value: path });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  function makeFile(path, name, metadata, permissions) {
    const handle = {
      kind: "file",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "file" &&
          rootByHandle.get(other) === rootByHandle.get(handle) && other.__path === path;
      },
      async queryPermission(options) {
        return (!options || options.mode !== "readwrite" || permissions.writable)
          ? "granted" : "denied";
      },
      async requestPermission(options) {
        return this.queryPermission(options);
      },
      async getFile() {
        diagnose("file_handle_get_file_started", permissions.requestId, { method: "getFile" });
        try {
          const payload = nativeJson("readImagePackFile", permissions.requestId, path);
          const binary = atob(payload.data || "");
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          const file = new File([bytes], name, {
            type: payload.mimeType || metadata.mimeType || "application/octet-stream",
            lastModified: Number(payload.lastModified || metadata.lastModified || 0)
          });
          diagnose("file_handle_get_file_succeeded", permissions.requestId, { fileSize: payload.size || 0 });
          return file;
        } catch (error) {
          diagnose("file_handle_get_file_failed", permissions.requestId, {
            errorName: error && error.name || "Error",
            stack: String(error && error.stack || "")
          });
          throw error;
        }
      },
      async createWritable() {
        if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
        let buffer = new Uint8Array(0);
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
            } else {
              throw new TypeError("Unsupported image-pack writable data.");
            }
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
            nativeJson("writeImagePackFile", permissions.requestId, path,
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
    Object.defineProperty(handle, "__path", { value: path });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  const showDirectoryPicker = async function(options) {
    options = options || {};
    const mode = options.mode || "readwrite";
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid directory picker mode.");
    const selection = await nativeRequest(mode);
    if (mode === "readwrite" && selection.writable !== true) {
      throw domError("NotAllowedError", "Android did not grant write access to the selected folder.");
    }
    const permissions = {
      root: {}, writable: selection.writable === true,
      requestId: selection.requestId || ""
    };
    diagnose("directory_handle_created", permissions.requestId, {
      directoryHandle: true, handleKind: "directory", writable: permissions.writable
    });
    return makeDirectory("", selection.name || "Image packs", permissions);
  };

  try {
    Object.defineProperty(window, "showDirectoryPicker", {
      configurable: true,
      enumerable: true,
      writable: true,
      value: showDirectoryPicker
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
    isTopFrame: window === window.top
  });

  window.__waywardReadPersistedImagePackRoot = function() {
    const info = nativeJson("getImagePackFolderInfo", "");
    const permissions = { root: {}, writable: info.writable === true, requestId: "" };
    return makeDirectory("", info.name || "Image packs", permissions);
  };
})();