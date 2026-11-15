package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

public final class RemotePairingActivity extends Activity {
    private TextView status;
    private boolean processing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(48, 32, 48, 32);
        page.setBackgroundColor(Color.rgb(246, 247, 248));

        TextView title = new TextView(this);
        title.setText("PC 페어링");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(24, 27, 31));
        title.setGravity(Gravity.CENTER);
        page.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Windows Remote Host Manager에 표시된 QR을 런처 내부 스캐너로 읽습니다.");
        status.setTextSize(14);
        status.setTextColor(Color.rgb(88, 98, 108));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 18;
        page.addView(status, statusParams);

        TextView cancel = new TextView(this);
        cancel.setText("취소");
        cancel.setTextSize(15);
        cancel.setTextColor(Color.rgb(52, 74, 94));
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, 52);
        cancelParams.topMargin = 24;
        page.addView(cancel, cancelParams);
        cancel.setOnClickListener(view -> finish());

        setContentView(page);
        beginScan();
    }

    private void beginScan() {
        if (processing || isFinishing()) return;
        status.setText("PC 화면의 QR 코드를 스캔하세요.");
        GmsBarcodeScanning.getClient(this).startScan()
                .addOnSuccessListener(barcode -> handleQr(barcode))
                .addOnCanceledListener(this::finish)
                .addOnFailureListener(error -> showFailure(
                        "내부 QR 스캐너를 실행하지 못했습니다. Google Play services의 코드 스캐너 모듈을 확인하세요.\n" +
                        error.getMessage()));
    }

    private void handleQr(Barcode barcode) {
        if (processing) return;
        String raw = barcode == null ? null : barcode.getRawValue();
        if (raw == null || raw.trim().isEmpty() || raw.length() > 32768) {
            showFailure("QR 데이터가 비어 있거나 허용 크기를 초과했습니다.");
            return;
        }
        processing = true;
        status.setText("QR 서명·Host Identity·TLS 인증서 지문 검증 중…");

        new Thread(() -> {
            try {
                RemoteHostClient.verifyAndStoreQr(this, raw);
                RemoteHostConnection host = RemoteHostStore.loadConnection(this);
                if (host == null) throw new IllegalStateException("검증한 PC 연결 정보를 저장하지 못했습니다.");

                runOnUiThread(() -> status.setText("검증 완료 · PC Manager의 기기 승인 대기 중…"));
                RemoteHostClient.pairAndWaitForApproval(this, host);

                JSONArray roots = RemoteHostClient.getWorkspaceRoots(this, host);
                if (roots.length() == 0) {
                    throw new IllegalStateException("기기 등록은 승인됐지만 PC에 허용된 폴더가 없습니다. Windows Manager에서 폴더를 추가하세요.");
                }
                String selected = RemoteHostStore.getSelectedRootId(this);
                boolean exists = false;
                for (int i = 0; i < roots.length(); i++) {
                    JSONObject root = roots.optJSONObject(i);
                    if (root != null && selected.equals(root.optString("rootId", ""))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists && roots.length() == 1) {
                    JSONObject root = roots.getJSONObject(0);
                    RemoteHostStore.saveSelectedRoot(this, root.getString("rootId"),
                            root.optString("displayName", "PC 폴더"));
                }

                runOnUiThread(() -> {
                    status.setText("PC 승인 완료 · 영구 페어링과 세션 연결이 준비됐습니다.");
                    Toast.makeText(this, "Wayward PC 페어링 완료", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showFailure(error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }, "Wayward-Remote-Pairing").start();
    }

    private void showFailure(String message) {
        processing = false;
        if (isFinishing()) return;
        status.setText("페어링 실패\n" + (message == null ? "알 수 없는 오류" : message));
        new AlertDialog.Builder(this)
                .setTitle("페어링 실패")
                .setMessage(message == null ? "알 수 없는 오류가 발생했습니다." : message)
                .setNegativeButton("닫기", (dialog, which) -> finish())
                .setPositiveButton("다시 스캔", (dialog, which) -> beginScan())
                .show();
    }
}