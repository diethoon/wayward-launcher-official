package com.wayward.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class NovelAiCaptchaJobMonitorDeviceTest {
    private static final String CAPTCHA_FAILURE =
            "{\"promptId\":\"captcha-test-job\",\"status\":\"error\","
                    + "\"error\":\"Recaptcha token is required for trial generation\","
                    + "\"errorType\":\"novelai_trial_recaptcha_required\"}";

    private static final class RejectingWebView extends WebView {
        RejectingWebView(Context context) { super(context); }
        @Override public boolean post(Runnable action) { return false; }
    }

    @Test public void asyncTrialCaptchaFailureOpensOfficialHandoffOnce() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        assertNotNull("Launcher launch intent is missing", launchIntent);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity host = instrumentation.startActivitySync(launchIntent);
        assertNotNull("Launcher host Activity did not start", host);

        AtomicReference<WebView> sourceWeb = new AtomicReference<>();
        AtomicReference<RejectingWebView> rejectingWeb = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            WebView web = new WebView(host);
            host.addContentView(web, new ViewGroup.LayoutParams(1, 1));
            sourceWeb.set(web);
            rejectingWeb.set(new RejectingWebView(host));
        });

        try (ServerSocket server = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(15000);
            NovelAiCaptchaJobMonitor.setStatusPortForTesting(server.getLocalPort());

            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = CAPTCHA_FAILURE.getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json; charset=utf-8\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.flush();
                } catch (Throwable error) {
                    serverFailure.set(error);
                }
            }, "captcha-test-status-server");
            serverThread.start();

            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    NovelAiCaptchaActivity.class.getName(), null, false);
            assertFalse("Rejected WebView.post() must reset the launch guard",
                    NovelAiCaptchaActivity.openFrom(rejectingWeb.get()));

            byte[] queuedResponse = ("{\"promptId\":\"captcha-test-job\","
                    + "\"status\":\"queued\",\"workflow\":\"nai\"}")
                    .getBytes(StandardCharsets.UTF_8);
            NovelAiCaptchaJobMonitor.startFromGenerateResponse(
                    context, sourceWeb.get(), queuedResponse, false);

            Activity captcha = instrumentation.waitForMonitorWithTimeout(monitor, 20000);
            instrumentation.removeMonitor(monitor);
            assertNotNull("Asynchronous status failure did not open CAPTCHA Activity", captcha);
            assertTrue("Unexpected Activity type",
                    captcha instanceof NovelAiCaptchaActivity);

            assertFalse("A second CAPTCHA Activity must be suppressed while one is open",
                    NovelAiCaptchaActivity.openFrom(sourceWeb.get()));

            java.lang.reflect.Field webField = NovelAiCaptchaActivity.class.getDeclaredField("web");
            java.lang.reflect.Field statusField = NovelAiCaptchaActivity.class.getDeclaredField("pageStatus");
            webField.setAccessible(true);
            statusField.setAccessible(true);
            WebView captchaWeb = (WebView) webField.get(captcha);
            android.widget.TextView pageStatus = (android.widget.TextView) statusField.get(captcha);
            assertNotNull("CAPTCHA Activity WebView was not created", captchaWeb);
            assertNotNull("CAPTCHA Activity page status was not created", pageStatus);
            instrumentation.runOnMainSync(() -> captchaWeb.loadUrl("https://novelai.net:1/captcha-test-load-failure"));
            long deadline = System.currentTimeMillis() + 10000;
            boolean loadFailureShown = false;
            while (System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync();
                final String[] state = new String[1];
                instrumentation.runOnMainSync(() -> state[0] = pageStatus.getText().toString());
                if (state[0] != null && state[0].contains("페이지 로딩 실패")) {
                    loadFailureShown = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue("WebView main-frame load failure was not surfaced to the user",
                    loadFailureShown);

            serverThread.join(5000);
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                captcha.finish();
                host.finish();
            });
        } finally {
            NovelAiCaptchaJobMonitor.setStatusPortForTesting(8189);
        }
    }
}
