package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pairing and connection management UI for the official Wayward launcher. */
public final class RemoteHostActivity extends Activity {
    private static final String SERVICE_TYPE = "_wayward-remote-host._tcp.";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, RemoteHostMdnsCandidate> discoveredHosts = new LinkedHashMap<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private Runnable discoveryTimeout;
    private EditText deviceNameInput;
    private TextView statusText;
    private TextView connectionText;
    private Button pairButton;
    private Button authenticateButton;
    private Button rootsButton;
    private volatile RemoteHostConnection connection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            RemoteHostStore.getDeviceKeyPair(this);
            RemoteHostStore.saveDeviceName(this, RemoteHostStore.getDeviceName(this));
            connection = RemoteHostStore.loadConnection(this);
        } catch (Exception error) {
            setStatus("Remote Host 기기 Identity 초기화 실패: " + error.getMessage());
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(26));

        TextView title = new TextView(this);
        title.setText("PC Remote Play");
        title.setTextSize(22f);
        title.setTextColor(0xff202833);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title, matchWidth());

        TextView hint = new TextView(this);
        hint.setText("PC에서 Remote Host LAN 접속을 켠 뒤 QR을 스캔하거나 같은 네트워크에서 Host를 검색하세요. 연결에는 Host 서명과 TLS 인증서 핀이 모두 검증됩니다.");
        hint.setTextSize(12f);
        hint.setTextColor(0xff667080);
        hint.setPadding(0, dp(8), 0, dp(10));
        page.addView(hint, matchWidth());

        deviceNameInput = new EditText(this);
        deviceNameInput.setSingleLine(true);
        deviceNameInput.setText(RemoteHostStore.getDeviceName(this));
        deviceNameInput.setHint("이 Android 기기의 이름");
        page.addView(deviceNameInput, matchWidth());

        Button scan = addButton(page, "PC Host QR 스캔", this::scanQr);
        addButton(page, "같은 LAN에서 PC Host 검색", this::startLanDiscovery);
        addButton(page, "LAN 검색 중지", () -> stopLanDiscovery(true));

        connectionText = new TextView(this);
        connectionText.setTextSize(12f);
        connectionText.setTextColor(0xff374151);
        connectionText.setPadding(0, dp(12), 0, dp(12));
        page.addView(connectionText, matchWidth());

        pairButton = addButton(page, "PC Manager 승인 요청", () -> runTask("기기 등록", () -> {
            RemoteHostConnection host = requireConnection();
            RemoteHostStore.saveDeviceName(this, deviceNameInput.getText().toString());
            String result = RemoteHostClient.pairAndWaitForApproval(this, host);
            return result + "\n기기 등록이 승인되었습니다.";
        }));

        authenticateButton = addButton(page, "기존 등록 기기 재인증", () -> runTask("세션 인증", () -> {
            RemoteHostConnection host = requireConnection();
            String token = RemoteHostClient.authenticate(this, host);
            JSONObject current = RemoteHostClient.requestJson(host, "/v1/auth/session", "GET", null, "Bearer " + token);
            if (!current.optBoolean("authenticated")) {
                throw new SecurityException("PC Host가 이 Device Session을 인증하지 않았습니다.");
            }
            return "PC Host 재인증 완료\nDevice ID: " + current.optString("deviceId") +
                    "\n권한: " + current.optInt("permissions") +
                    "\nSession 만료: " + current.optString("sessionExpiresAtUtc");
        }));

        rootsButton = addButton(page, "PC 게임 폴더 선택", () -> runTask("PC 폴더 조회", () -> {
            RemoteHostConnection host = requireConnection();
            RemoteHostClient.authenticate(this, host);
            JSONArray roots = RemoteHostClient.getWorkspaceRoots(this, host);
            showRootPicker(roots);
            return "PC 허용 폴더 " + roots.length() + "개를 조회했습니다.";
        }));

        addButton(page, "PC 연결 해제", () -> confirmDisconnect());

        statusText = new TextView(this);
        statusText.setTextSize(12f);
        statusText.setTextColor(0xff475569);
        statusText.setPadding(0, dp(12), 0, 0);
        page.addView(statusText, matchWidth());

        setContentView(new ScrollView(this) {{
            setFillViewport(true);
            addView(page);
        }});
        refreshConnectionUi();
        if (connection == null) setStatus("PC Host가 등록되지 않았습니다. QR 스캔 또는 LAN 검색을 시작하세요.");
        else setStatus("저장된 PC Host 연결 정보를 불러왔습니다. 접근 Token은 다시 발급받습니다.");
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinHeight(dp(44));
        GlassButtonStyle.bind(this, button, false, 12f);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = matchWidth();
        params.bottomMargin = dp(7);
        parent.addView(button, params);
        return button;
    }

    private void scanQr() {
        IntentIntegrator scanner = new IntentIntegrator(this);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("Windows Remote Host Manager에서 생성한 QR 코드를 스캔하세요.");
        scanner.setBeepEnabled(false);
        scanner.setOrientationLocked(false);
        scanner.initiateScan();
    }

    @Deprecated
    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentIntegrator.IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) return;
        String contents = result.getContents();
        if (contents == null || contents.trim().isEmpty()) {
            setStatus("QR 스캔이 취소되었습니다.");
            return;
        }
        runTask("Host QR 검증", () -> {
            String message = RemoteHostClient.verifyAndStoreQr(this, contents);
            connection = RemoteHostStore.loadConnection(this);
            runOnUiThread(this::refreshConnectionUi);
            return message;
        });
    }

    private void startLanDiscovery() {
        stopLanDiscovery(false);
        synchronized (discoveredHosts) { discoveredHosts.clear(); }
        nsdManager = getSystemService(NsdManager.class);
        if (nsdManager == null) {
            setStatus("Android NSD 검색을 사용할 수 없습니다.");
            return;
        }

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                setStatus("같은 LAN에서 Wayward Remote Host를 검색하고 있습니다…");
            }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info == null || info.getServiceType() == null ||
                        !info.getServiceType().toLowerCase(java.util.Locale.ROOT).contains("_wayward-remote-host._tcp")) {
                    return;
                }
                nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        setStatus("LAN Host 주소 확인 실패: " + errorCode);
                    }
                    @Override public void onServiceResolved(NsdServiceInfo resolved) {
                        try {
                            InetAddress address = resolved.getHost();
                            Map<String, byte[]> attributes = resolved.getAttributes();
                            String hostId = txt(attributes, "hostid");
                            String keyFingerprint = txt(attributes, "keyfp");
                            String certificateFingerprint = txt(attributes, "certfp");
                            String protocol = txt(attributes, "protocol");
                            if (address == null || hostId == null || keyFingerprint == null ||
                                    certificateFingerprint == null || !"1".equals(protocol)) return;
                            RemoteHostMdnsCandidate candidate = new RemoteHostMdnsCandidate(
                                    hostId, address, resolved.getPort(), keyFingerprint, certificateFingerprint);
                            synchronized (discoveredHosts) {
                                discoveredHosts.put(candidate.hostId, candidate);
                            }
                            setStatus("LAN PC Host 검색됨: " + candidate.hostId + " · " + address.getHostAddress());
                        } catch (Exception error) {
                            setStatus("LAN Host 검색 응답을 무시했습니다: " + error.getMessage());
                        }
                    }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                setStatus("LAN Host 검색 항목이 네트워크에서 사라졌습니다.");
            }
            @Override public void onDiscoveryStopped(String serviceType) {
                // Search results are shown by the timeout or Stop action.
            }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryListener = null;
                setStatus("LAN 검색 시작 실패: " + errorCode);
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discoveryListener = null;
                setStatus("LAN 검색 중지 실패: " + errorCode);
            }
        };
        discoveryListener = listener;
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        discoveryTimeout = () -> stopLanDiscovery(true);
        mainHandler.postDelayed(discoveryTimeout, 6500L);
    }

    private static String txt(Map<String, byte[]> attributes, String key) {
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void stopLanDiscovery(boolean showResults) {
        if (discoveryTimeout != null) mainHandler.removeCallbacks(discoveryTimeout);
        discoveryTimeout = null;
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); }
            catch (IllegalArgumentException ignored) { }
        }
        discoveryListener = null;
        if (showResults) showDiscoveredHosts();
    }

    private void showDiscoveredHosts() {
        List<RemoteHostMdnsCandidate> entries;
        synchronized (discoveredHosts) { entries = new ArrayList<>(discoveredHosts.values()); }
        if (entries.isEmpty()) {
            setStatus("같은 LAN에서 PC Host를 찾지 못했습니다. Windows Manager에서 LAN 접속을 켰는지 확인하세요.");
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            RemoteHostMdnsCandidate item = entries.get(i);
            labels[i] = item.hostId.substring(0, 8) + " · " +
                    item.address.getHostAddress() + ":" + item.port;
        }
        new AlertDialog.Builder(this)
                .setTitle("검색된 PC Host")
                .setItems(labels, (dialog, which) -> runTask("LAN Host 서명 검증", () -> {
                    RemoteHostConnection found = RemoteHostClient.discoverHost(entries.get(which));
                    RemoteHostStore.saveConnection(this, found);
                    connection = found;
                    runOnUiThread(this::refreshConnectionUi);
                    return "LAN PC Host 신원 및 TLS 인증서 핀 검증 완료\nHost ID: " + found.hostId;
                }))
                .setNegativeButton("취소", null)
                .show();
    }

    private void showRootPicker(JSONArray roots) {
        List<String> rootIds = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < roots.length(); i++) {
            JSONObject root = roots.optJSONObject(i);
            if (root == null) continue;
            String id = root.optString("rootId", "");
            if (id.isEmpty()) continue;
            rootIds.add(id);
            labels.add(root.optString("displayName", "PC 폴더") + " · " + id.substring(0, Math.min(8, id.length())));
        }
        if (rootIds.isEmpty()) {
            runOnUiThread(() -> setStatus("PC Manager에서 게임 폴더를 허용 목록에 먼저 추가하세요."));
            return;
        }
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("모바일 게임에서 사용할 PC 폴더 선택")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    RemoteHostStore.saveSelectedRoot(this, rootIds.get(which), labels.get(which));
                    setStatus("PC 게임 폴더가 선택되었습니다: " + labels.get(which) +
                            "\n홈 화면에서 PC Remote Play 모드를 선택하세요.");
                    refreshConnectionUi();
                })
                .setNegativeButton("취소", null)
                .show());
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("PC 연결 정보 삭제")
                .setMessage("이 Android 런처에서 PC 주소와 선택 폴더 정보를 삭제합니다. PC Manager에 등록된 기기 Identity는 유지됩니다. 서버의 기기 등록을 폐기하려면 PC Manager에서 별도로 해제해야 합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    RemoteHostStore.clearConnection(this);
                    connection = null;
                    refreshConnectionUi();
                    setStatus("로컬 PC 연결 정보를 삭제했습니다. PC의 등록 기기 기록은 변경하지 않았습니다.");
                })
                .show();
    }

    private RemoteHostConnection requireConnection() throws Exception {
        RemoteHostConnection value = connection;
        if (value == null) value = RemoteHostStore.loadConnection(this);
        if (value == null) throw new IllegalStateException("먼저 PC Host QR을 스캔하거나 LAN Host 검색을 실행하세요.");
        return value;
    }

    private void refreshConnectionUi() {
        try {
            connection = RemoteHostStore.loadConnection(this);
            if (connection == null) {
                connectionText.setText("PC Host 미등록");
            } else {
                connectionText.setText("등록 PC: " + connection.hostId +
                        "\n주소: " + connection.baseUrl +
                        "\n선택 폴더: " + (RemoteHostStore.getSelectedRootLabel(this).isEmpty()
                                ? "미선택" : RemoteHostStore.getSelectedRootLabel(this)));
            }
            boolean registered = connection != null;
            pairButton.setEnabled(registered);
            authenticateButton.setEnabled(registered);
            rootsButton.setEnabled(registered);
        } catch (Exception error) {
            connectionText.setText("저장된 PC 정보 검증 실패: " + error.getMessage());
            pairButton.setEnabled(false);
            authenticateButton.setEnabled(false);
            rootsButton.setEnabled(false);
        }
    }

    private void runTask(String label, java.util.concurrent.Callable<String> action) {
        setStatus(label + " 실행 중…");
        executor.execute(() -> {
            try {
                String result = action.call();
                runOnUiThread(() -> setStatus(result));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus(label + " 실패: " +
                        error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
        });
    }

    private void setStatus(String value) {
        runOnUiThread(() -> {
            if (statusText != null) statusText.setText(value);
        });
    }

    @Override protected void onDestroy() {
        stopLanDiscovery(false);
        executor.shutdownNow();
        super.onDestroy();
    }
}
