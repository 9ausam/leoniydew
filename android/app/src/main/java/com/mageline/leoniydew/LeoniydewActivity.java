package com.mageline.leoniydew;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;

public final class LeoniydewActivity extends NativeActivity {

    static {
        System.loadLibrary("leoniydew");
    }

    private AlertDialog editorDialog;
    private long activeRequestId;

    private static native void onTextResult(long requestId, String text, boolean accepted);
    private static native void onDisplayDpi(float dpi);
    private static native void onDocumentPicked(String path, String error);
    private static native void onFolderPicked(String files, String error);
    private static native void onFilesCopied(String error);
    private static native void onMic(boolean granted);
    private static native void onHeadphones(boolean on);
    private static native void onInterrupted();
    private static native void onLeaving();
    private static native void onBack();
    private static native void onLibraryFolderPicked(String path, String error);

    private static final int OPEN_DOCUMENT = 1;
    private static final int OPEN_FOLDER = 2;
    private static final int RECORD_AUDIO = 3;
    private static final int LIBRARY_FOLDER = 4;
    private static final int READ_STORAGE = 5;

    private long openLimit;
    private AudioFocusRequest focus;
    private volatile boolean quitting;

    // Audio device monitoring.
    private final AudioDeviceCallback outputs = new AudioDeviceCallback() {

        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            onHeadphones(headphones());
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            onHeadphones(headphones());
        }
    };

    @Override
    protected void onCreate(Bundle state) {

        // Publish physical density before NativeActivity starts the Rust app thread.
        onDisplayDpi(getResources().getDisplayMetrics().xdpi);

        super.onCreate(state);

        hideSystemBars();

        getSystemService(AudioManager.class)
                .registerAudioDeviceCallback(outputs, null);

        // Back goes to the app (Android 13 and up): closes topmost message/popup/panel,
        // with none open it asks "Exit Leoniydew?" (#147; see quit).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {

                        @Override
                        public void onBackInvoked() {
                            LeoniydewActivity.onBack();
                        }
                    }
            );
        }
    }

    // Check connected headphones.
    private boolean headphones() {

        AudioManager audio =
                getSystemService(AudioManager.class);

        AudioDeviceInfo[] devices =
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        for (AudioDeviceInfo device : devices) {

            switch (device.getType()) {

                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    return true;
            }
        }

        return false;
    }

    // Available microphone inputs.
    public String inputs() {

        StringBuilder list = new StringBuilder();

        AudioManager audio =
                getSystemService(AudioManager.class);

        AudioDeviceInfo[] devices =
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS);

        for (AudioDeviceInfo device : devices) {

            String label;

            switch (device.getType()) {

                case AudioDeviceInfo.TYPE_BUILTIN_MIC:

                    String at = device.getAddress();

                    label = at.isEmpty() || at.equals("bottom")
                            ? "Built-in mic"
                            : "Built-in mic (" + at + ")";

                    break;

                case AudioDeviceInfo.TYPE_WIRED_HEADSET:

                    label = "Headset mic";
                    break;

                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:

                    label = "USB: " + device.getProductName();
                    break;

                default:
                    continue;
            }

            list.append(device.getId())
                    .append('\0')
                    .append(label)
                    .append('\0');
        }

        return list.toString();
    }

    // Microphone permission.
    public boolean askMic() {

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        RECORD_AUDIO
                );
            }
        });

        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int request,
            String[] permissions,
            int[] results) {

        if (request != RECORD_AUDIO) {

            super.onRequestPermissionsResult(
                    request,
                    permissions,
                    results
            );

            return;
        }

        onMic(
                results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED
        );
    }

    // Audio recording focus.
    public void recording(final boolean on) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                AudioManager audio =
                        getSystemService(AudioManager.class);

                if (focus != null) {
                    audio.abandonAudioFocusRequest(focus);
                }

                focus = null;

                if (!on) {
                    return;
                }

                AudioAttributes music =
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(
                                        AudioAttributes.CONTENT_TYPE_MUSIC
                                )
                                .build();

                focus = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                        .setAudioAttributes(music)
                        .setOnAudioFocusChangeListener(
                                new AudioManager.OnAudioFocusChangeListener() {

                                    @Override
                                    public void onAudioFocusChange(int change) {

                                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {

                                            onInterrupted();
                                        }
                                    }
                                }
                        )
                        .build();

                if (audio.requestAudioFocus(focus)
                        == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {

                    onInterrupted();
                }
            }
        });
    }

    @Override
    protected void onPause() {

        // Once quitting, the app thread has ended and wrote what it keeps: no frame would answer.
        if (!quitting) {
            onLeaving();
        }

        super.onPause();

        onInterrupted();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {

        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemBars();
        }
    }

    // Immersive mode.
    private void hideSystemBars() {

        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            window.setDecorFitsSystemWindows(false);

            WindowInsetsController controller =
                    window.getInsetsController();

            if (controller == null) {
                return;
            }

            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );

            controller.hide(WindowInsets.Type.systemBars());

        } else {

            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {

        super.onConfigurationChanged(configuration);

        onDisplayDpi(getResources().getDisplayMetrics().xdpi);
    }

    // Request screen orientation.
    public void requestOrientation(final int orientation) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                if (isFinishing() || isDestroyed()) {
                    return;
                }

                setRequestedOrientation(orientation);
            }
        });
    }

    // Native text editor.
    public void editText(
            final long requestId,
            final String text,
            final boolean multiline) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                if (isFinishing() || isDestroyed()) {

                    onTextResult(requestId, text, false);
                    return;
                }

                if (editorDialog != null) {
                    editorDialog.dismiss();
                }

                final EditText input = new EditText(LeoniydewActivity.this);

                input.setInputType(
                        InputType.TYPE_CLASS_TEXT
                        | (multiline
                        ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        : 0)
                );

                input.setSingleLine(!multiline);

                input.setImeOptions(
                        EditorInfo.IME_FLAG_NO_FULLSCREEN
                        | (multiline
                        ? EditorInfo.IME_ACTION_NONE
                        : EditorInfo.IME_ACTION_DONE)
                );

                input.setText(text);
                input.setSelection(input.length());

                final boolean[] accepted = {false};

                final AlertDialog dialog =
                        new AlertDialog.Builder(LeoniydewActivity.this)
                                .setTitle("Edit text")
                                .setView(input)
                                .setPositiveButton(
                                        "Done",
                                        new DialogInterface.OnClickListener() {

                                            @Override
                                            public void onClick(
                                                    DialogInterface whichDialog,
                                                    int whichButton) {

                                                accepted[0] = true;
                                            }
                                        }
                                )
                                .setNegativeButton("Cancel", null)
                                .create();

                dialog.setOnDismissListener(
                        new DialogInterface.OnDismissListener() {

                            @Override
                            public void onDismiss(DialogInterface whichDialog) {

                                if (editorDialog == dialog) {

                                    editorDialog = null;
                                    activeRequestId = 0;
                                }

                                onTextResult(
                                        requestId,
                                        input.getText().toString(),
                                        accepted[0]
                                );
                            }
                        }
                );

                input.setOnEditorActionListener(
                        new TextView.OnEditorActionListener() {

                            @Override
                            public boolean onEditorAction(
                                    TextView view,
                                    int action,
                                    android.view.KeyEvent event) {

                                if (!multiline
                                        && action == EditorInfo.IME_ACTION_DONE) {

                                    accepted[0] = true;

                                    dialog.dismiss();

                                    return true;
                                }

                                return false;
                            }
                        }
                );

                editorDialog = dialog;
                activeRequestId = requestId;

                dialog.show();

                input.requestFocus();

                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                );
            }
        });
    }

    // Cancel text editing.
    public void cancelTextEdit(final long requestId) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                if (editorDialog != null
                        && activeRequestId == requestId) {

                    editorDialog.dismiss();
                }
            }
        });
    }

    // Open document picker.
    public void openDocument(final long maxBytes) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                openLimit = maxBytes;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");

                try {

                    startActivityForResult(intent, OPEN_DOCUMENT);

                } catch (RuntimeException e) {

                    onDocumentPicked(
                            null,
                            String.valueOf(e.getMessage())
                    );
                }
            }
        });
    }

    // Open folder picker.
    public void openFolder() {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                try {

                    startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                            OPEN_FOLDER
                    );

                } catch (RuntimeException e) {

                    onFolderPicked(
                            null,
                            String.valueOf(e.getMessage())
                    );
                }
            }
        });
    }

    // Library folders (#144): whole storage access (All files access from Android 11, storage permission before).
    public boolean allFiles() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }

        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Ask for All files access (Android 11+), or the storage permission before.
    public void askAllFiles() {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

                    requestPermissions(
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            READ_STORAGE
                    );

                    return;
                }

                Uri app = Uri.parse("package:" + getPackageName());

                try {

                    startActivity(
                            new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    app
                            )
                    );

                } catch (RuntimeException e) {

                    startActivity(
                            new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            )
                    );
                }
            }
        });
    }

    // SETTINGS `Add folder` (#144): folder picker; the folder's path goes back to Rust.
    public void openLibraryFolder() {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                try {

                    startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                            LIBRARY_FOLDER
                    );

                } catch (RuntimeException e) {

                    onLibraryFolderPicked(
                            null,
                            String.valueOf(e.getMessage())
                    );
                }
            }
        });
    }

    // A picked folder's path on the phone, from its document id in the system's storage provider.
    private static String storagePath(Uri tree) {

        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) {
            return null;
        }

        String id = DocumentsContract.getTreeDocumentId(tree);

        int colon = id.indexOf(':');

        if (colon < 0) {
            return null;
        }

        String volume = id.substring(0, colon);
        String rest = id.substring(colon + 1);

        String root = volume.equals("primary")
                ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + volume;

        return rest.isEmpty() ? root : root + "/" + rest;
    }

    // Process selected folder.
    private void folderPicked(final Uri tree) {

        new Thread(new Runnable() {

            @Override
            public void run() {

                try {

                    String root =
                            DocumentsContract.getTreeDocumentId(tree);

                    String name = displayName(
                            DocumentsContract.buildDocumentUriUsingTree(
                                    tree,
                                    root
                            )
                    );

                    StringBuilder files = new StringBuilder();

                    list(tree, root, name + "/", 0, files);

                    onFolderPicked(files.toString(), null);

                } catch (Exception e) {

                    onFolderPicked(
                            null,
                            String.valueOf(e.getMessage())
                    );
                }
            }
        }).start();
    }

    // List files recursively.
    private void list(
            Uri tree,
            String id,
            String path,
            int depth,
            StringBuilder files) {

        if (depth > 32) {
            return;
        }

        String[] columns = {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE
        };

        Uri children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree,
                        id
                );

        try (Cursor c = getContentResolver().query(
                children,
                columns,
                null,
                null,
                null)) {

            while (c != null && c.moveToNext()) {

                String child = c.getString(0);
                String name = part(c.getString(1));

                if (name.startsWith(".")) {
                    continue;
                }

                if (Document.MIME_TYPE_DIR.equals(c.getString(2))) {

                    list(
                            tree,
                            child,
                            path + name + "/",
                            depth + 1,
                            files
                    );

                } else {

                    Uri uri =
                            DocumentsContract.buildDocumentUriUsingTree(
                                    tree,
                                    child
                            );

                    files.append(path)
                            .append(name)
                            .append('\0')
                            .append(uri)
                            .append('\0');
                }
            }
        }
    }

    // Copy selected files.
    public void copyFiles(final String files) {

        final String[] parts = files.split("\0");

        new Thread(new Runnable() {

            @Override
            public void run() {

                try {

                    for (int i = 0; i + 1 < parts.length; i += 2) {

                        copyFile(
                                Uri.parse(parts[i]),
                                new File(parts[i + 1])
                        );
                    }

                    onFilesCopied(null);

                } catch (Exception e) {

                    onFilesCopied(String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    // Copy one file atomically.
    private void copyFile(Uri from, File to) throws IOException {

        File dir = to.getParentFile();

        if (dir == null) {
            throw new IOException("Invalid destination folder");
        }

        File part = new File(
                dir,
                "." + to.getName() + ".part"
        );

        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException(
                    dir + ": the folder cannot be made"
            );
        }

        InputStream in = null;
        OutputStream out = null;

        try {

            in = getContentResolver().openInputStream(from);

            if (in == null) {
                throw new IOException(
                        from + ": the file cannot be read"
                );
            }

            out = new FileOutputStream(part);

            copy(in, out, Long.MAX_VALUE);

            out.flush();

        } catch (IOException e) {

            part.delete();
            throw e;

        } catch (RuntimeException e) {

            part.delete();
            throw e;

        } finally {

            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }

            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (!part.renameTo(to)) {

            part.delete();

            throw new IOException(
                    to + ": the file cannot be written"
            );
        }
    }

    // Copy bytes.
    private static void copy(
            InputStream in,
            OutputStream out,
            long left) throws IOException {

        byte[] buffer = new byte[1 << 16];

        for (int n; left > 0; left -= n) {

            n = in.read(
                    buffer,
                    0,
                    (int) Math.min(buffer.length, left)
            );

            if (n < 0) {
                break;
            }

            out.write(buffer, 0, n);
        }
    }

    // Open user folder.
    public void showFolder(final String path) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                Uri uri = LeoniydewDocuments.folderUri(
                        LeoniydewActivity.this,
                        path
                );

                Intent view = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                                uri,
                                DocumentsContract.Document.MIME_TYPE_DIR
                        )
                        .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );

                Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .putExtra(
                                DocumentsContract.EXTRA_INITIAL_URI,
                                uri
                        );

                Intent[] intents = {view, tree};

                for (Intent intent : intents) {

                    try {

                        startActivity(intent);
                        return;

                    } catch (RuntimeException ignored) {
                    }
                }

                if (isFinishing() || isDestroyed()) {
                    return;
                }

                new AlertDialog.Builder(LeoniydewActivity.this)
                        .setMessage(
                                "No app on this phone can open folders. "
                                + "The folder is " + path
                        )
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    // Share files.
    public String share(String paths, String type) {

        ArrayList<Uri> uris = new ArrayList<Uri>();

        for (String path : paths.split("\0")) {

            Uri uri = LeoniydewDocuments.documentUri(this, path);

            if (uri == null) {
                return path + ": not in the user folder";
            }

            uris.add(uri);
        }

        Intent send = new Intent(
                uris.size() == 1
                ? Intent.ACTION_SEND
                : Intent.ACTION_SEND_MULTIPLE
        )
                .setType(type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (uris.size() == 1) {

            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));

        } else {

            send.putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    uris
            );
        }

        ClipData clip = ClipData.newRawUri(null, uris.get(0));

        for (int i = 1; i < uris.size(); i++) {

            clip.addItem(new ClipData.Item(uris.get(i)));
        }

        send.setClipData(clip);

        final Intent chooser = Intent.createChooser(send, null);

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                startActivity(chooser);
            }
        });

        return null;
    }

    // Current network status.
    public int network() {

        ConnectivityManager connectivity =
                getSystemService(ConnectivityManager.class);

        Network network = connectivity.getActiveNetwork();

        NetworkCapabilities caps = network == null
                ? null
                : connectivity.getNetworkCapabilities(network);

        if (caps == null
                || !caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                )) {

            return 0;
        }

        return connectivity.isActiveNetworkMetered() ? 2 : 1;
    }

    // Download file.
    public String download(String url, String path) {

        File file = new File(path);

        HttpURLConnection http = null;

        try {

            http = (HttpURLConnection)
                    new URL(url).openConnection();

            http.setConnectTimeout(30000);
            http.setReadTimeout(60000);

            http.setRequestProperty(
                    "User-Agent",
                    "Leoniydew"
            );

            long have = file.length();

            if (have > 0) {

                http.setRequestProperty(
                        "Range",
                        "bytes=" + have + "-"
                );
            }

            int code = http.getResponseCode();

            if (code == 416 && have > 0) {
                return null;
            }

            if (code != 200 && code != 206) {

                return "The server answered "
                        + code
                        + " ("
                        + http.getResponseMessage()
                        + ")";
            }

            if (!"https".equals(http.getURL().getProtocol())) {
                return "The download left HTTPS";
            }

            try (
                    InputStream in = http.getInputStream();
                    OutputStream out = new FileOutputStream(
                            file,
                            code == 206
                    )
            ) {

                copy(in, out, Long.MAX_VALUE);
            }

            return null;

        } catch (UnknownHostException e) {

            return "offline";

        } catch (ConnectException e) {

            return "offline";

        } catch (Exception e) {

            return String.valueOf(e.getMessage());

        } finally {

            if (http != null) {
                http.disconnect();
            }
        }
    }

    // Available storage.
    public long usableSpace() {

        return getFilesDir().getUsableSpace();
    }

    // Install downloaded APK.
    public boolean installApk(final String name) {

        if (!getPackageManager().canRequestPackageInstalls()) {

            final Uri app = Uri.parse(
                    "package:" + getPackageName()
            );

            runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    startActivity(
                            new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    app
                            )
                    );
                }
            });

            return false;
        }

        final Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                        LeoniydewUpdate.uri(this, name),
                        LeoniydewUpdate.APK
                )
                .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                startActivity(install);
            }
        });

        return true;
    }

    // Activity result.
    @Override
    protected void onActivityResult(
            int request,
            int result,
            Intent data) {

        if (request == LIBRARY_FOLDER) {

            Uri tree =
                    result == RESULT_OK && data != null
                    ? data.getData()
                    : null;

            String path = tree == null ? null : storagePath(tree);

            if (tree == null) {

                onLibraryFolderPicked(null, null);

            } else if (path == null) {

                onLibraryFolderPicked(
                        null,
                        "Pick a folder on the phone's storage or an SD card."
                );

            } else {

                onLibraryFolderPicked(path, null);
            }

            return;
        }

        if (request != OPEN_DOCUMENT && request != OPEN_FOLDER) {

            super.onActivityResult(request, result, data);
            return;
        }

        final Uri uri =
                result == RESULT_OK && data != null
                ? data.getData()
                : null;

        if (request == OPEN_FOLDER) {

            if (uri == null) {
                onFolderPicked(null, null);
            } else {
                folderPicked(uri);
            }

            return;
        }

        if (uri == null) {

            onDocumentPicked(null, null);
            return;
        }

        final long limit = openLimit;

        new Thread(new Runnable() {

            @Override
            public void run() {

                try {

                    File dir = new File(
                            getCacheDir(),
                            "picked"
                    );

                    File[] old = dir.listFiles();

                    if (old != null) {

                        for (File f : old) {
                            f.delete();
                        }
                    }

                    if (!dir.isDirectory() && !dir.mkdirs()) {

                        throw new IOException("No cache folder");
                    }

                    File file = new File(
                            dir,
                            displayName(uri)
                    );

                    try (
                            InputStream in =
                                    getContentResolver().openInputStream(uri);

                            OutputStream out =
                                    new FileOutputStream(file)
                    ) {

                        if (in == null) {

                            throw new IOException(
                                    "The file cannot be read"
                            );
                        }

                        copy(in, out, limit + 1);
                    }

                    onDocumentPicked(file.getPath(), null);

                } catch (Exception e) {

                    onDocumentPicked(
                            null,
                            String.valueOf(e.getMessage())
                    );
                }
            }
        }).start();
    }

    // Provider display name.
    private String displayName(Uri uri) {

        String name = null;

        String[] columns = {
                OpenableColumns.DISPLAY_NAME
        };

        Cursor c = null;

        try {

            c = getContentResolver().query(
                    uri,
                    columns,
                    null,
                    null,
                    null
            );

            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }

        } catch (RuntimeException ignored) {

            // Use URI name as fallback.

        } finally {

            if (c != null) {
                try {
                    c.close();
                } catch (RuntimeException ignored) {
                }
            }
        }

        if (name == null) {
            name = uri.getLastPathSegment();
        }

        name = part(name);

        return name.isEmpty()
                || name.equals(".")
                || name.equals("..")
                ? "file"
                : name;
    }

    // Safe path component.
    private static String part(String name) {

        return name == null
                ? ""
                : name.replace('/', '_')
                      .replace('\\', '_')
                      .replace('\0', '_');
    }

    // Exit (#147, Rust `android::quit`): finish the task, and end the process once destroyed.
    // Backstop ends the process 3 s later anyway, as everything is written.
    public void quit() {

        quitting = true;

        runOnUiThread(new Runnable() {

            @Override
            public void run() {

                finishAndRemoveTask();
            }
        });

        Thread backstop = new Thread(new Runnable() {

            @Override
            public void run() {

                try {

                    Thread.sleep(3000);

                } catch (InterruptedException ignored) {
                }

                System.exit(0);
            }
        });

        backstop.setDaemon(true);
        backstop.start();
    }

    @Override
    protected void onDestroy() {

        if (editorDialog != null) {
            editorDialog.dismiss();
        }

        getSystemService(AudioManager.class)
                .unregisterAudioDeviceCallback(outputs);

        super.onDestroy();

        onDisplayDpi(0);

        if (quitting) {
            System.exit(0);
        }
    }
}