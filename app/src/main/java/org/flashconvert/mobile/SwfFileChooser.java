package org.flashconvert.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Manual SWF file chooser.
 *
 * IMPORTANT: never filter by a SWF MIME type. Samsung DocumentsUI can omit
 * files with an unrecognized Flash extension even when ACTION_OPEN_DOCUMENT
 * is invoked with wildcard MIME. Instead, show file names from directories
 * selected by the user (no automatic search or recursive background scan).
 *
 * Runtime conversion is done by SwfCore/SwfFrameRenderer/SwfMp4Encoder, with
 * NO playback, WebView, screen recording, or network access.
 */
final class SwfFileChooser {
    static final int REQUEST_FILE = 101;
    static final int REQUEST_TREE = 102;
    static final int REQUEST_STORAGE = 103;

    private final Activity activity;
    private final Consumer<Uri> onSelected;
    private final Consumer<String> onStatus;
    private boolean waitingForAllFiles = false;

    SwfFileChooser(Activity activity, Consumer<Uri> onSelected, Consumer<String> onStatus) {
        this.activity = activity;
        this.onSelected = onSelected;
        this.onStatus = onStatus;
    }

    void open() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(activity)
                    .setTitle("إظهار جميع ملفات SWF")
                    .setMessage("متصفح الملفات في سامسونغ قد يخفي صيغة SWF. سيفتح التطبيق المجلدات مباشرة بأسماء الملفات الحقيقية. يحتاج ذلك إلى منح إذن الوصول إلى جميع الملفات. لن يبحث عن الملفات تلقائيًا ولن يرفعها إلى أي جهة.")
                    .setPositiveButton("السماح بعرض الملفات", (d, w) -> askForAllFilesAccess())
                    .setNeutralButton("بدون الإذن: اختر مجلدًا", (d, w) -> openTree())
                    .setNegativeButton("إلغاء", null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT < 30 &&
                activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        openDownloads();
    }

    private void askForAllFilesAccess() {
        waitingForAllFiles = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                waitingForAllFiles = false;
                onStatus.accept("تعذّر فتح إعدادات الملفات. اختر مجلدًا بالطريقة البديلة.");
                openTree();
            }
        }
    }

    void onResume() {
        if (!waitingForAllFiles) return;
        waitingForAllFiles = false;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            openDownloads();
        } else {
            onStatus.accept("لم يُمنح إذن الملفات. يمكن اختيار مجلد دون هذا الإذن.");
            new AlertDialog.Builder(activity)
                    .setTitle("إذن عرض الملفات")
                    .setMessage("لم يتم السماح بعرض الملفات. هل تريد اختيار مجلد من مدير ملفات أندرويد ثم تصفحه داخل التطبيق؟")
                    .setPositiveButton("اختيار مجلد", (d, w) -> openTree())
                    .setNegativeButton("إلغاء", null)
                    .show();
        }
    }

    void onRequestPermissionsResult(int requestCode, int[] grants) {
        if (requestCode != REQUEST_STORAGE) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) openDownloads();
        else openTree();
    }

    private void openDownloads() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || !dir.isDirectory()) dir = Environment.getExternalStorageDirectory();
        showLocalDirectory(dir);
    }

    private void showLocalDirectory(File dir) {
        File root = Environment.getExternalStorageDirectory();
        final String rootPath, path;
        try {
            rootPath = root.getCanonicalPath();
            path = dir.getCanonicalPath();
            if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
                onStatus.accept("المجلد المحدد خارج ذاكرة الهاتف المتاحة.");
                return;
            }
        } catch (IOException e) {
            onStatus.accept("تعذّر قراءة المجلد: " + e.getMessage());
            return;
        }

        File[] entries = dir.listFiles();
        if (entries == null) {
            new AlertDialog.Builder(activity)
                    .setTitle("تعذّر فتح المجلد")
                    .setMessage("لا يمكن قراءة هذا المجلد. يمكن اختيار مجلد آخر أو استخدام النافذة البديلة.")
                    .setPositiveButton("اختيار مجلد", (d, w) -> openTree())
                    .setNegativeButton("إغلاق", null)
                    .show();
            return;
        }

        Arrays.sort(entries, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
            boolean l = left.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
            boolean r = right.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
            if (l != r) return l ? -1 : 1;
            return left.getName().compareToIgnoreCase(right.getName());
        });

        final boolean canBack = !path.equals(rootPath);
        final int maximum = Math.min(entries.length, 2000);
        String[] names = new String[maximum + (canBack ? 1 : 0)];
        if (canBack) names[0] = "⬆ رجوع إلى المجلد السابق";

        int flashCount = 0;
        for (int i = 0; i < maximum; i++) {
            File item = entries[i];
            boolean isSwf = item.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
            if (isSwf && item.isFile()) flashCount++;
            names[i + (canBack ? 1 : 0)] = (item.isDirectory() ? "📁 " : (isSwf ? "🎞 " : "📄 ")) + item.getName();
        }

        String location = path.equals(rootPath) ? "الذاكرة الداخلية" : path.substring(rootPath.length() + 1);
        onStatus.accept("المجلد: " + location + " — ملفات SWF: " + flashCount);
        String hint = flashCount == 0 ? " (لا توجد ملفات SWF في هذا المجلد)" : " (" + flashCount + " ملف SWF)";
        new AlertDialog.Builder(activity)
                .setTitle(location + hint)
                .setItems(names, (dialog, choice) -> {
                    if (canBack && choice == 0) {
                        showLocalDirectory(dir.getParentFile());
                        return;
                    }
                    File selected = entries[choice - (canBack ? 1 : 0)];
                    if (selected.isDirectory()) {
                        showLocalDirectory(selected);
                    } else if (selected.getName().toLowerCase(Locale.ROOT).endsWith(".swf")) {
                        onSelected.accept(Uri.fromFile(selected));
                    } else {
                        Toast.makeText(activity, "هذا الملف ليس SWF. اختر ملفًا ينتهي بـ .swf", Toast.LENGTH_LONG).show();
                        showLocalDirectory(dir);
                    }
                })
                .setPositiveButton("التنزيلات", (d, w) -> openDownloads())
                .setNeutralButton("الذاكرة الداخلية", (d, w) -> showLocalDirectory(root))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void openTree() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(i, REQUEST_TREE);
        } catch (Exception e) {
            onStatus.accept("تعذّر فتح مدير الملفات: " + e.getMessage());
            openStandardFilePicker();
        }
    }

    private void openStandardFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(i, REQUEST_FILE);
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_FILE) {
            if (resultCode == Activity.RESULT_OK && intent != null && intent.getData() != null)
                onSelected.accept(intent.getData());
            return true;
        }
        if (requestCode == REQUEST_TREE) {
            if (resultCode == Activity.RESULT_OK && intent != null && intent.getData() != null) {
                Uri tree = intent.getData();
                try {
                    browseTree(tree, DocumentsContract.getTreeDocumentId(tree), new ArrayList<>());
                } catch (Exception e) {
                    onStatus.accept("تعذّر فتح المجلد: " + e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    private static final class Entry {
        final String id, name;
        final boolean isDirectory;
        Entry(String id, String name, boolean dir) { this.id = id; this.name = name; this.isDirectory = dir; }
    }

    // SAF returns every file in the chosen directory, independent of extension MIME.
    // Only the chosen directory is read; no automatic recursive filesystem search.
    private void browseTree(Uri tree, String id, ArrayList<String> parentIds) {
        final ArrayList<Entry> result = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String[] fields = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = activity.getContentResolver().query(children, fields, null, null, null)) {
                if (cursor == null) throw new IOException("تعذّر قراءة الملفات");
                int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int typeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext() && result.size() < 2000) {
                    String fileId = cursor.getString(idCol), name = cursor.getString(nameCol), type = cursor.getString(typeCol);
                    if (fileId == null || name == null) continue;
                    result.add(new Entry(fileId, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(type)));
                }
            }
        } catch (Exception e) {
            onStatus.accept("تعذّر قراءة ملفات المجلد: " + e.getMessage());
            return;
        }

        Collections.sort(result, (l, r) -> {
            if (l.isDirectory != r.isDirectory) return l.isDirectory ? -1 : 1;
            boolean a = l.name.toLowerCase(Locale.ROOT).endsWith(".swf");
            boolean b = r.name.toLowerCase(Locale.ROOT).endsWith(".swf");
            if (a != b) return a ? -1 : 1;
            return l.name.compareToIgnoreCase(r.name);
        });

        boolean back = !parentIds.isEmpty();
        String[] names = new String[result.size() + (back ? 1 : 0)];
        if (back) names[0] = "⬆ الرجوع إلى المجلد السابق";
        int swfCount = 0;
        for (int i = 0; i < result.size(); i++) {
            Entry x = result.get(i);
            boolean swf = x.name.toLowerCase(Locale.ROOT).endsWith(".swf") && !x.isDirectory;
            if (swf) swfCount++;
            names[i + (back ? 1 : 0)] = (x.isDirectory ? "📁 " : swf ? "🎞 " : "📄 ") + x.name;
        }
        onStatus.accept("ملفات SWF في المجلد المحدد: " + swfCount);
        new AlertDialog.Builder(activity)
                .setTitle("ملفات المجلد — SWF: " + swfCount)
                .setItems(names, (d, choice) -> {
                    if (back && choice == 0) {
                        ArrayList<String> p = new ArrayList<>(parentIds);
                        String previous = p.remove(p.size() - 1);
                        browseTree(tree, previous, p);
                        return;
                    }
                    Entry chosen = result.get(choice - (back ? 1 : 0));
                    if (chosen.isDirectory) {
                        ArrayList<String> p = new ArrayList<>(parentIds);
                        p.add(id);
                        browseTree(tree, chosen.id, p);
                    } else if (chosen.name.toLowerCase(Locale.ROOT).endsWith(".swf")) {
                        onSelected.accept(DocumentsContract.buildDocumentUriUsingTree(tree, chosen.id));
                    } else {
                        Toast.makeText(activity, "اختر ملفًا بصيغة SWF", Toast.LENGTH_SHORT).show();
                        browseTree(tree, id, parentIds);
                    }
                })
                .setPositiveButton("مجلد آخر", (d, w) -> openTree())
                .setNegativeButton("إغلاق", null)
                .show();
    }
}