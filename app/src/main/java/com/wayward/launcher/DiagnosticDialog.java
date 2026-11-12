package com.wayward.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Visible, copyable diagnostics for failures that must not be hidden in Android's truncated Toast UI. */
final class DiagnosticDialog {
    private DiagnosticDialog() {}

    static void show(Activity activity, String message) {
        if (activity == null || activity.isFinishing()) return;
        String details = message == null ? "상세 정보 없음" : message;
        String logs = readLogs(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 4));

        TextView intro = new TextView(activity);
        intro.setText("검증 실패 상세 정보입니다. 아래 내용을 복사해 전달해 주세요.");
        intro.setTextColor(Color.DKGRAY);
        intro.setTextSize(13);
        panel.addView(intro);

        ScrollView scroll = new ScrollView(activity);
        TextView body = new TextView(activity);
        body.setText(details);
        body.setTextIsSelectable(true);
        body.setTextColor(Color.BLACK);
        body.setTextSize(12);
        body.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        body.setTextIsSelectable(true);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 220)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        Button copyDetails = new Button(activity);
        copyDetails.setText("검증 상세 정보 복사");
        copyDetails.setOnClickListener(v -> copy(activity, "Wayward DAL 검증 상세", details));
        actions.addView(copyDetails, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        Button copyLogs = new Button(activity);
        copyLogs.setText("진단 로그 전체 복사");
        copyLogs.setOnClickListener(v -> copy(activity, "Wayward 런처 진단 로그", logs));
        actions.addView(copyLogs, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        Button copyAll = new Button(activity);
        copyAll.setText("상세 정보 + 로그 복사");
        copyAll.setOnClickListener(v -> copy(activity, "Wayward DAL 진단", details + "\n\n--- LOCAL DIAGNOSTICS ---\n" + logs));
        actions.addView(copyAll, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        panel.addView(actions);

        new android.app.AlertDialog.Builder(activity)
                .setTitle("Google 연결 진단")
                .setView(panel)
                .setPositiveButton("닫기", null)
                .setCancelable(true)
                .show();
    }

    private static String readLogs(Context context) {
        StringBuilder out = new StringBuilder();
        File dir = new File(context.getFilesDir(), "diagnostics");
        appendFile(out, new File(dir, "launcher.previous.ndjson"));
        appendFile(out, new File(dir, "launcher.ndjson"));
        if (out.length() == 0) return "진단 로그가 아직 없습니다.";
        return out.toString();
    }

    private static void appendFile(StringBuilder out, File file) {
        if (!file.isFile()) return;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0L, length - 512L * 1024L);
            byte[] buffer = new byte[8192];
            input.seek(start);
            int count;
            out.append("\n--- ").append(file.getName()).append(" ---\n");
            while ((count = input.read(buffer)) != -1) {
                out.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            out.append("\n[로그 읽기 실패: ").append(error.getClass().getSimpleName()).append("]\n");
        }
    }

    private static void copy(Context context, String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(context, "클립보드를 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value == null ? "" : value));
        Toast.makeText(context, "진단 정보를 복사했습니다. 이 대화에 붙여넣어 주세요.", Toast.LENGTH_LONG).show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
