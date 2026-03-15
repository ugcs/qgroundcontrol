package org.mavlink.qgroundcontrol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.qtproject.qt.android.bindings.QtActivity;

public class QGCActivity extends QtActivity {
    private static final String TAG = QGCActivity.class.getSimpleName();
    private static final String MULTICAST_LOCK_TAG = "QGroundControl";

    private static volatile QGCActivity m_instance = null;

    private static final int IMPORT_FILE_REQUEST_CODE = 42;
    private static String s_importDestPath = "";

    private WifiManager.MulticastLock m_wifiMulticastLock;
    private volatile QGCStoragePermissionController m_storagePermissionController;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        m_instance = this;

        nativeInit();
        setupMulticastLock();

        QGCUsbSerialManager.initialize(this);
        QGCSDLManager.initialize(this);
        m_storagePermissionController = new QGCStoragePermissionController(this);
    }

    @Override
    protected void onPause() {
        QGCSDLManager.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        QGCSDLManager.onResume();
    }

    @Override
    protected void onDestroy() {
        try {
            QGCSDLManager.cleanup();
            releaseMulticastLock();
            QGCUsbSerialManager.cleanup(this);
        } catch (final Exception e) {
            QGCLogger.e(TAG, "Exception onDestroy()", e);
        }

        if (m_instance == this) {
            m_instance = null;
        }
        super.onDestroy();
    }

    /**
     * Sets up a multicast lock to allow multicast packets.
     */
    private void setupMulticastLock() {
        if (m_wifiMulticastLock == null) {
            final WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                QGCLogger.w(TAG, "WifiManager is unavailable; multicast lock not acquired");
                return;
            }
            m_wifiMulticastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG);
            m_wifiMulticastLock.setReferenceCounted(false);
        }

        if (m_wifiMulticastLock == null) {
            return;
        }
        m_wifiMulticastLock.acquire();
        QGCLogger.d(TAG, "Multicast lock: " + m_wifiMulticastLock.toString());
    }

    /**
     * Releases the multicast lock if held.
     */
    private void releaseMulticastLock() {
        if (m_wifiMulticastLock != null && m_wifiMulticastLock.isHeld()) {
            m_wifiMulticastLock.release();
            QGCLogger.d(TAG, "Multicast lock released.");
        }
    }

    public static String getSDCardPath() {
        final QGCActivity activity = m_instance;
        if (activity == null) {
            QGCLogger.e(TAG, "Activity instance is null");
            return "";
        }
        if (activity.m_storagePermissionController == null) {
            activity.m_storagePermissionController = new QGCStoragePermissionController(activity);
        }
        return activity.m_storagePermissionController.getSDCardPath();
    }

    /**
     * Checks and requests storage permissions for SD card access.
     * Android 11+ uses app-scoped storage and does not require runtime storage
     * permissions for app-owned directories.
     *
     * @return true if permissions are granted, false otherwise
     */
    public static boolean checkStoragePermissions() {
        final QGCActivity activity = m_instance;
        if (activity == null) {
            QGCLogger.e(TAG, "Activity instance is null");
            return false;
        }
        if (activity.m_storagePermissionController == null) {
            activity.m_storagePermissionController = new QGCStoragePermissionController(activity);
        }
        return activity.m_storagePermissionController.checkStoragePermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (m_storagePermissionController == null) {
            m_storagePermissionController = new QGCStoragePermissionController(this);
        }

        final Boolean granted = m_storagePermissionController.onRequestPermissionsResult(requestCode, grantResults);
        if (granted == null) {
            return;
        }

        nativeStoragePermissionsResult(granted);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (QGCSDLManager.handleMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (QGCSDLManager.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // Native C++ functions
    public native boolean nativeInit();
    public native void qgcLogDebug(final String message);
    public native void qgcLogWarning(final String message);
    public native void nativeStoragePermissionsResult(boolean granted);

    public native void onImportResult(final String filePath);

   /**
    * Opens Android's native file picker using ACTION_OPEN_DOCUMENT.
    * The selected file will be copied to the provided destination directory.
    *
    * @param destPath Fully-qualified path of the destination Missions directory.
    */
   public static void openFileImportDialog(final String destPath) {
       if (m_instance == null) {
           Log.e(TAG, "Activity instance is null");
           return;
       }
       s_importDestPath = (destPath != null) ? destPath : "";
       final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
       intent.addCategory(Intent.CATEGORY_OPENABLE);
       intent.setType("*/*");
       m_instance.startActivityForResult(intent, IMPORT_FILE_REQUEST_CODE);
   }

   @Override
   protected void onActivityResult(int requestCode, int resultCode, Intent data) {
       if (requestCode == IMPORT_FILE_REQUEST_CODE) {
           if (resultCode == Activity.RESULT_OK && data != null) {
               final Uri uri = data.getData();
               if (uri != null) {
                   final String importedPath = copyFileToDestination(uri, s_importDestPath);
                   onImportResult(importedPath != null ? importedPath : "");
               } else {
                   Log.w(TAG, "onActivityResult: null URI for file import");
                   onImportResult("");
               }
           }
           // User cancelled — do not call back so the QML side takes no action.
           return;
       }
       super.onActivityResult(requestCode, resultCode, data);
   }

   /**
    * Copies a file identified by a content URI to the specified destination directory.
    *
    * @param uri     Content URI of the source file returned by ACTION_OPEN_DOCUMENT.
    * @param destDir Fully-qualified path of the destination directory.
    * @return Fully-qualified path of the copied file, or null on failure.
    */
   private String copyFileToDestination(final Uri uri, final String destDir) {
       String displayName = "";
       try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
           if (cursor != null && cursor.moveToFirst()) {
               final int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
               if (nameIndex >= 0) {
                   displayName = cursor.getString(nameIndex);
               }
           }
       } catch (Exception e) {
           Log.e(TAG, "Failed to query display name for URI: " + uri, e);
       }

       if (displayName.isEmpty()) {
           // Fallback: try to extract a usable filename from the last path segment of the URI.
           // For content URIs, getLastPathSegment() may return something like
           // "primary:Download/mission.plan" — extract only the portion after the last '/'.
           final String lastSegment = uri.getLastPathSegment();
           if (lastSegment != null && !lastSegment.isEmpty()) {
               final int lastSlash = lastSegment.lastIndexOf('/');
               displayName = (lastSlash >= 0) ? lastSegment.substring(lastSlash + 1) : lastSegment;
               // Remove any leading "primary:" or similar scheme prefix.
               final int colonIdx = displayName.indexOf(':');
               if (colonIdx >= 0 && colonIdx < displayName.length() - 1) {
                   displayName = displayName.substring(colonIdx + 1);
               }
           }
           if (displayName.isEmpty()) {
               displayName = "imported_file";
           }
       }

       if (destDir == null || destDir.isEmpty()) {
           Log.e(TAG, "copyFileToDestination: destination directory is empty");
           return null;
       }

       if (!displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".plan")) {
           Log.w(TAG, "Rejected non-.plan file: " + displayName);
           return null;
       }

       final File destDirectory = new File(destDir);
       if (!destDirectory.exists() && !destDirectory.mkdirs()) {
           Log.e(TAG, "Failed to create destination directory: " + destDir);
           return null;
       }

       final File destFile = new File(destDirectory, displayName);
       try (InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(destFile)) {
           final byte[] buffer = new byte[8192];
           int bytesRead;
           while ((bytesRead = is.read(buffer)) != -1) {
               fos.write(buffer, 0, bytesRead);
           }
           Log.i(TAG, "File imported successfully to: " + destFile.getAbsolutePath());
           return destFile.getAbsolutePath();
       } catch (Exception e) {
           Log.e(TAG, "Failed to copy file to destination", e);
           return null;
       }
   }
}
