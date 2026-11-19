package com.wayward.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

public final class OfficialUpdateManagerTest {
    private static Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Class<?> type = Class.forName("com.wayward.launcher.OfficialUpdateManager");
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test
    public void sha256DigestRequiresExactly64HexCharacters() throws Exception {
        String hash = "a".repeat(64);
        assertEquals(hash, invoke("digestFromAsset", new Class<?>[]{String.class}, "sha256:" + hash));
        assertEquals(hash, invoke("digestFromAsset", new Class<?>[]{String.class}, "sha256:" + hash.toUpperCase()));
        assertEquals("", invoke("digestFromAsset", new Class<?>[]{String.class}, "sha512:" + hash));
        assertEquals("", invoke("digestFromAsset", new Class<?>[]{String.class}, "sha256:" + hash.substring(1)));
        assertEquals("", invoke("digestFromAsset", new Class<?>[]{String.class}, "sha256:" + hash + "00"));
    }

    @Test
    public void checksumFileMatchesOnlyTheExpectedApkBasename() throws Exception {
        String hash = "0123456789abcdef".repeat(4);
        String apk = "WAL-Android-Launcher-1.0.0.apk";
        assertEquals(hash, invoke("findChecksum", new Class<?>[]{String.class, String.class},
                hash + "  " + apk, apk));
        assertEquals(hash, invoke("findChecksum", new Class<?>[]{String.class, String.class},
                hash + " *nested/path/" + apk, apk));
        assertEquals(hash, invoke("findChecksum", new Class<?>[]{String.class, String.class},
                hash + "  nested\\folder\\" + apk, apk));
        assertEquals("", invoke("findChecksum", new Class<?>[]{String.class, String.class},
                hash + "  WAL-Android-Launcher-1.0.1.apk", apk));
        assertEquals("", invoke("findChecksum", new Class<?>[]{String.class, String.class},
                "not-a-checksum", apk));
    }

    @Test
    public void releaseDownloadAllowlistRejectsLookalikeHostsAndHttp() throws Exception {
        assertTrue((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com/wal-studio/WAL-Official-Releases/releases/download/v1.0.0/WAL-Android-Launcher-1.0.0.apk"));
        assertTrue((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com/wal-studio/wayward-launcher-official/releases/download/v1.0.0/WAL-Android-Launcher-1.0.0.apk"));
        assertFalse((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "http://github.com/wal-studio/WAL-Official-Releases/releases/download/v1.0.0/file.apk"));
        assertFalse((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com/wal-studio/WAL-Official-Releases/blob/main/file.apk"));
        assertFalse((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com/wal-studio/WAL-Official-Releases/releases/tag/v1.0.0"));
        assertFalse((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com.evil.example/wal-studio/WAL-Official-Releases/file.apk"));
        assertFalse((Boolean) invoke("isAllowedReleaseUrl", new Class<?>[]{String.class},
                "https://github.com/wal-studio/WAL-Official-Releases.evil/file.apk"));
    }

    @Test
    public void stableReleaseTagsRejectPrereleaseAndMalformedVersions() throws Exception {
        Class<?> type = Class.forName("com.wayward.launcher.OfficialUpdateManager");
        Field field = type.getDeclaredField("STABLE_TAG");
        field.setAccessible(true);
        Pattern pattern = (Pattern) field.get(null);
        assertTrue(pattern.matcher("v1.0.0").matches());
        assertTrue(pattern.matcher("2.14.3").matches());
        assertFalse(pattern.matcher("v01.0.0").matches());
        assertFalse(pattern.matcher("v0.1.0").matches());
        assertFalse(pattern.matcher("v1.01.0").matches());
        assertFalse(pattern.matcher("v1.0.01").matches());
        assertFalse(pattern.matcher("v1.0.0-beta.1").matches());
        assertFalse(pattern.matcher("latest").matches());
        assertFalse(pattern.matcher("v1.0").matches());
    }
}
