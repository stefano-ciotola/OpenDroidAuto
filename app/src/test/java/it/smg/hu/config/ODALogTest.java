package it.smg.hu.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class ODALogTest {

    @Test
    public void resolveWritableLogBaseDirReturnsExistingDirectory() throws Exception {
        File root = createTempDir("oda_log_existing");
        try {
            File resolved = ODALog.resolveWritableLogBaseDir(root);
            assertEquals(root, resolved);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void resolveWritableLogBaseDirCreatesMissingLeafUnderExistingParent() throws Exception {
        File root = createTempDir("oda_log_leaf");
        try {
            File target = new File(root.getAbsolutePath() + File.separator + "ODA" + File.separator);
            File resolved = ODALog.resolveWritableLogBaseDir(target);
            assertEquals(target, resolved);
            assertTrue(resolved != null && resolved.isDirectory());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void resolveWritableLogBaseDirDoesNotCreateMissingParentMountpoint() throws Exception {
        File root = createTempDir("oda_log_mount");
        try {
            File missingParent = new File(root, "unmounted");
            File target = new File(missingParent, "ODA");
            File resolved = ODALog.resolveWritableLogBaseDir(target);
            assertNull(resolved);
            assertFalse(missingParent.exists());
            assertFalse(target.exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void resolveWritableLogBaseDirRejectsRegularFile() throws Exception {
        File root = createTempDir("oda_log_file");
        try {
            File regularFile = new File(root, "not-a-dir");
            assertTrue(regularFile.createNewFile());
            assertNull(ODALog.resolveWritableLogBaseDir(regularFile));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void resolveWritableLogBaseDirRejectsNullAndEmptyPath() {
        assertNull(ODALog.resolveWritableLogBaseDir(null));
        assertNull(ODALog.resolveWritableLogBaseDir(new File("")));
    }

    private static File createTempDir(String prefix) throws Exception {
        File dir = File.createTempFile(prefix, "");
        if (!dir.delete() || !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create temp directory: " + dir);
        }
        return dir;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
