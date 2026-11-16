package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

public final class RemotePairingActivity extends Activity {
    private static final String TAG = "WaywardRemotePair";
    private TextView status;
    private boolean processing;
    private boolean scannerStarting;

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
        if (processing || scannerStarting || isFinishing()) return;
        scannerStarting = true;
        status.setText("QR 스캐너 모듈 준비 상태 확인 중…");
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        Log.i(TAG, "stage=scanner_module_check result=started");

        ModuleInstall.getClient(this).areModulesAvailable(scanner)
                .addOnSuccessListener(availability -> {
                    if (isFinishing()) return;
                    if (availability.areModulesAvailable()) {
                        Log.i(TAG, "stage=scanner_module_check result=ready");
                        launchScanner(scanner);
                    } else {
                        installScannerModule(scanner);
                    }
                })
                .addOnFailureListener(error -> {
                    scannerStarting = false;
                    Log.e(TAG, "stage=scanner_module_check result=failed error=" +
                            error.getClass().getSimpleName());
                    showFailure("QR 스캐너 모듈 준비 상태를 확인하지 못했습니다. Google Play services를 확인한 뒤 다시 시도하세요.");
                });
    }

    private void installScannerModule(GmsBarcodeScanner scanner) {
        if (isFinishing()) return;
        status.setText("QR 스캐너 모듈 설치 중… 설치가 끝나면 자동으로 다시 실행합니다.");
        Log.i(TAG, "stage=scanner_module_install result=started");
        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .build();
        ModuleInstall.getClient(this).installModules(request)
                .addOnSuccessListener(response -> {
                    if (isFinishing()) return;
                    Log.i(TAG, "stage=scanner_module_install result=completed");
                    scannerStarting = false;
                    beginScan();
                })
                .addOnFailureListener(error -> {
                    scannerStarting = false;
                    Log.e(TAG, "stage=scanner_module_install result=failed error=" +
                            error.getClass().getSimpleName());
                    showFailure("QR 스캐너 모듈 설치에 실패했습니다. Google Play services 연결 상태를 확인하고 다시 시도하세요.");
                });
    }

    private void launchScanner(GmsBarcodeScanner scanner) {
        if (isFinishing()) return;
        status.setText("PC 화면의 QR 코드를 화면 안에 맞춰 스캔하세요.");
        Log.i(TAG, "stage=qr_scan result=started format=QR_CODE auto_zoom=true");
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    scannerStarting = false;
                    handleQr(barcode);
                })
                .addOnCanceledListener(() -> {
                    scannerStarting = false;
                    Log.i(TAG, "stage=qr_scan result=cancelled");
                    finish();
                })
                .addOnFailureListener(error -> {
                    scannerStarting = false;
                    Log.e(TAG, "stage=qr_scan result=failed error=" +
                            error.getClass().getSimpleName());
                    showFailure("내부 QR 스캐너 실행에 실패했습니다. 잠시 후 다시 시도하세요.");
                });
    }

    private void handleQr(Barcode barcode) {
        if (processing) return;
        if (barcode == null || barcode.getFormat() != Barcode.FORMAT_QR_CODE) {
            Log.w(TAG, "stage=qr_recognition result=failed reason=not_qr");
            showFailure("QR 코드를 인식하지 못했습니다. PC 화면의 QR을 다시 스캔하세요.");
            return;
        }
        String raw = barcode.getRawValue();
        if (raw == null || raw.trim().isEmpty() || raw.length() > 32768) {
            Log.w(TAG, "stage=qr_recognition result=failed reason=empty_or_oversize");
            showFailure("QR 데이터가 비어 있거나 허용 크기를 초과했습니다.");
            return;
        }
        // Never log the QR payload: it contains the one-time enrollment nonce and trust material.
        Log.i(TAG, "stage=qr_recognition result=success chars=" + raw.length());
        processing = true;
        status.setText("QR 데이터 형식 확인 중…");

        new Thread(() -> processPairing(raw), "Wayward-Remote-Pairing").start();
    }

    private void processPairing(String raw) {
        final RemoteHostConnection scannedHost;
        try {
            Log.i(TAG, "stage=qr_parse result=started");
            scannedHost = RemoteHostConnection.fromQrJson(raw);
            Log.i(TAG, "stage=qr_parse result=success protocol=1");
        } catch (Exception error) {
            Log.e(TAG, "stage=qr_parse result=failed error=" + error.getClass().getSimpleName());
            runOnUiThread(() -> showFailure("QR 데이터 형식이 올바르지 않습니다: " +
                    safeError(error)));
            return;
        }

        try {
            statusOnUi("PC Host 서명 및 신원 확인 중…");
            RemoteHostClient.verifyAndStoreQr(this, scannedHost);
            RemoteHostConnection host = RemoteHostStore.loadConnection(this);
            if (host == null) throw new IllegalStateException("검증한 PC 연결 정보를 저장하지 못했습니다.");

            statusOnUi("PC 인증 및 기기 등록 요청 중…");
            Log.i(TAG, "stage=pc_pairing_auth result=started");
            RemoteHostClient.pairAndWaitForApproval(this, host);
            Log.i(TAG, "stage=user_approval result=accepted");

            String folderWarning = null;
            try {
                statusOnUi("페어링 완료 · PC 허용 폴더 확인 중…");
                JSONArray roots = RemoteHostClient.getWorkspaceRoots(this, host);
                if (roots.length() == 0) {
                    folderWarning = "페어링은 성공했지만 PC에서 허용한 폴더가 없습니다. Windows Manager에서 폴더 권한을 설정하세요.";
                } else {
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
                    Log.i(TAG, "stage=folder_access result=success roots=" + roots.length());
                }
            } catch (Exception folderError) {
                folderWarning = "페어링은 성공했습니다. 다만 PC 폴더 목록을 불러오지 못했습니다. PC 연결과 폴더 권한을 별도로 확인하세요. (" +
                        safeError(folderError) + ")";
                Log.e(TAG, "stage=folder_access result=failed error=" +
                        folderError.getClass().getSimpleName());
            }

            final String warning = folderWarning;
            runOnUiThread(() -> {
                status.setText(warning == null
                        ? "PC 승인 완료 · 페어링과 폴더 접근이 준비됐습니다."
                        : "PC 승인 완료 · 페어링 성공 / 폴더 접근 확인 필요");
                Toast.makeText(this, "Wayward PC 페어링 완료", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                if (warning == null) {
                    finish();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("페어링 완료 · 폴더 접근 별도 확인")
                            .setMessage(warning)
                            .setPositiveButton("확인", (dialog, which) -> finish())
                            .show();
                }
            });
        } catch (Exception error) {
            Log.e(TAG, "stage=pc_pairing_or_approval result=failed error=" +
                    error.getClass().getSimpleName());
            runOnUiThread(() -> showFailure(safeError(error)));
        }
    }

    private void statusOnUi(String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && status != null) status.setText(message);
        });
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        // Error text is UI-only; diagnostics above log exception classes, never payloads or credentials.
        return message;
    }

    private void showFailure(String message) {
        processing = false;
        scannerStarting = false;
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