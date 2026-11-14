package com.wayward.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Stores the user's own NovelAI persistent API token in the Android Keystore-backed registry. */
public final class NovelAiSettingsActivity extends Activity {
    private TextView status;
    private EditText tokenInput;
    private Button saveButton;
    private Button clearButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("NovelAI 설정");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(24));
        page.setBackgroundColor(Color.rgb(241, 246, 250));
        scroll.addView(page);

        TextView title = text("NOVELAI ACCOUNT", 11, Color.rgb(72, 111, 137), true);
        title.setLetterSpacing(0.1f);
        page.addView(title);
        TextView description = text(
                "본인 NovelAI 계정의 Persistent API Token을 등록합니다.\n"
                + "NovelAI 웹사이트 → Settings → Account → Get Persistent API Token에서 발급한 토큰을 붙여 넣으세요.\n\n"
                + "입력한 토큰은 image.novelai.net의 계정 구독 정보 조회로 먼저 검증합니다. 이미지 생성은 하지 않으며, 검증 성공 후에만 이 기기의 Android 보안 저장소에 암호화해 보관합니다.\n\n"
                + "앱 업데이트·게임 세션 변경·기기 재부팅 후에도 유지되며, 런처에서 직접 삭제하거나 앱 데이터/앱을 제거할 때만 사라집니다. 보안 키가 기기에 묶여 있어 다른 기기로 자동 이전되지는 않습니다.",
                12, Color.rgb(87, 103, 118), false);
        description.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(10);
        page.addView(description, descriptionParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(248,255,255,255),Color.argb(232,225,239,248),Color.argb(226,240,247,251)});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(150,255,255,255));
        card.setBackground(background);
        card.setElevation(dp(7));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(16);
        page.addView(card, cardParams);

        card.addView(text("계정 연결 상태", 14, Color.rgb(39, 58, 73), true));
        status = text("등록 상태 확인 중", 12, Color.rgb(85, 105, 120), false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        card.addView(status, statusParams);

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setTextSize(13);
        tokenInput.setTextColor(Color.rgb(32, 47, 60));
        tokenInput.setHintTextColor(Color.rgb(126, 143, 157));
        tokenInput.setHint("Persistent API Token 붙여넣기");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tokenInput.setPadding(dp(11), dp(9), dp(11), dp(9));
        tokenInput.setBackground(shape(Color.WHITE, Color.rgb(194, 211, 224), 11));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(50));
        inputParams.topMargin = dp(12);
        card.addView(tokenInput, inputParams);

        saveButton = action("연결 확인 및 저장", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(46));
        saveParams.topMargin = dp(10);
        card.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> verifyAndSave());

        clearButton = action("저장된 NAI 토큰 삭제", false);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-1, dp(42));
        clearParams.topMargin = dp(6);
        card.addView(clearButton, clearParams);
        clearButton.setOnClickListener(v -> confirmClear());

        try {
            String existing = NovelAiTokenStore.readToken(this);
            status.setText(existing.isEmpty()
                    ? "아직 토큰이 등록되지 않았습니다."
                    : "토큰 등록됨 · 이 기기에서 직접 삭제할 때까지 암호화 보관");
            clearButton.setEnabled(!existing.isEmpty());
        } catch (Exception error) {
            status.setText("토큰 저장소를 읽지 못했습니다. " + message(error));
            saveButton.setEnabled(false);
            clearButton.setEnabled(false);
        }

        setContentView(scroll);
    }

    private void verifyAndSave() {
        String token = tokenInput.getText().toString().trim();
        if (token.isEmpty() || token.length() > 4096 || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
            status.setText("토큰을 입력해 주세요. 줄바꿈 없이 4096자 이하여야 합니다.");
            return;
        }
        saveButton.setEnabled(false);
        clearButton.setEnabled(false);
        status.setText("NovelAI 계정 인증 확인 중… 이미지 생성 요청은 보내지 않습니다.");
        new Thread(() -> {
            String result;
            boolean valid = false;
            try {
                verifyPersistentToken(token);
                NovelAiTokenStore.saveToken(this, token);
                result = "연결 성공 · 토큰을 이 기기에 암호화해 저장했습니다. 직접 삭제하기 전까지 유지됩니다.";
                valid = true;
            } catch (Exception error) {
                result = message(error);
            }
            final boolean saved = valid;
            final String feedback = result;
            runOnUiThread(() -> {
                saveButton.setEnabled(true);
                clearButton.setEnabled(true);
                status.setText(feedback);
                if (saved) {
                    setResult(RESULT_OK);
                    Toast.makeText(this, "NovelAI 계정 연결을 저장했습니다.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }, "wayward-nai-token-check").start();
    }

    private void verifyPersistentToken(String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://image.novelai.net/user/subscription");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            int statusCode = connection.getResponseCode();
            if (statusCode == 200) return;
            if (statusCode == 401) throw new IllegalArgumentException("NovelAI 토큰이 유효하지 않거나 만료되었습니다. 사이트에서 Persistent API Token을 다시 발급해 주세요.");
            if (statusCode == 403) throw new IllegalArgumentException("NovelAI 계정의 API 접근이 거부되었습니다. 계정 상태와 토큰을 확인해 주세요.");
            throw new IllegalStateException("NovelAI 계정 확인 실패 · HTTP " + statusCode);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("NovelAI 토큰 삭제")
                .setMessage("이 기기에 암호화 저장된 Persistent API Token을 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    try {
                        NovelAiTokenStore.saveToken(this, "");
                        setResult(RESULT_OK);
                        Toast.makeText(this, "NovelAI 토큰을 삭제했습니다.", Toast.LENGTH_SHORT).show();
                        finish();
                    } catch (Exception error) {
                        status.setText("토큰 삭제 실패: " + message(error));
                    }
                }).show();
    }

    private Button action(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(48, 66, 84));
        GlassButtonStyle.bind(this, button, false, 12f);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable shape(int fill, int stroke, float radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fill);
        value.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) value.setStroke(dp(1), stroke);
        return value;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty() ? "알 수 없는 오류" : value;
    }
}
