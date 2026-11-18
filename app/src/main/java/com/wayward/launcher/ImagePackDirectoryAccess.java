package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    ֻǱ˞