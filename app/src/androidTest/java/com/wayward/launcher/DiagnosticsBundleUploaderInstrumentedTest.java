package com.wayward.launcher;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies the same failed diagnostics ZIP is retained for the next explicit upload attempt. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsBundleUploaderInstrumentedTest {
    @Test
    public void failedZipIsSelectedAgainAndMarkerClearsOnlyAfterSuccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "diagnostics");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File archive = new File(directory, "wayward-diagnostics-retry-instrumented.zip");
        File marker = new File(directory, archive.getName() + ".upload-pending");
        try {
            try (FileOutputStream output = new FileOutputStream(archive, false)) {
                output.write("instrumented-zip-fixture".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                output.getFD().sync();
            }
            DiagnosticsBundleUploader.clearPendingArchive(archive);
            assertNull(findExactPending(context, archive));
            DiagnosticsBundleUploader.rememberPendingArchive(archive);
            assertTrue(marker.isFile());
            assertEquals(archive.getCanonicalPath(),
                    findExactPending(context, archive).getCanonicalPath());

            DiagnosticsBundleUploader.clearPendingArchive(archive);
            assertTrue(!marker.exists());
            assertNull(findExactPending(context, archive));
        } finally {
            DiagnosticsBundleUploader.clearPendingArchive(archive);
            if (archive.exists()) archive.delete();
        }
    }

    private static File findExactPending(Context context, File expected) {
        File found = DiagnosticsBundleUploader.findPendingArchive(context);
        return found != null && found.getName().equals(expected.getName()) ? found : null;
    }
}
