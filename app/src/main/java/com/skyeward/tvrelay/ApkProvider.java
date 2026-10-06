package com.skyeward.tvrelay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 替代 AndroidX FileProvider（本项目不引 AndroidX）。
 * Android 7.0 起跨进程把 APK 交给安装器必须用 content://，file:// 会直接抛
 * FileUriExposedException。
 */
public class ApkProvider extends ContentProvider {

    /** 只放行 TinyHttp 生成的文件名：received-<随机数>.apk；别的名字一律拒绝。 */
    private static final String RECEIVED_NAME = "received-[0-9A-Za-z-]+\\.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("只读打开: " + mode);
        }
        // URI 来自安装器（外部进程），只认本应用生成的文件名，绝不拿它去拼路径
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches(RECEIVED_NAME)) {
            throw new FileNotFoundException("不允许的路径: " + uri);
        }
        File apk = new File(getContext().getCacheDir(), name);
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 返回 null 会让安装器拿不到 MIME，安装被拒。 */
    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
