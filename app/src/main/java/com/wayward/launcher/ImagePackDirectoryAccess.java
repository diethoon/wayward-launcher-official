package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On-demand SAF access for the image-pack directory chooser.
 * Only the current directory is queried when requested; no whole-tree scan,
 * index, file copy, or image cache is created.
 */
final class ImagePackDirectoryAccess {
    static final int REQUEST_CODE = 4106;
    private static final String PREFS = "wayward_launcher";
    private static final String TAG = "WaywardImagePackSAF";
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_STREAM_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_OPEN_STREAMS = 4;
    private static final long STREAM_IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final WebView webView;
    private final String preferenceKey;
    private final String writablePreferenceKey;
    private final St¶»§q«^