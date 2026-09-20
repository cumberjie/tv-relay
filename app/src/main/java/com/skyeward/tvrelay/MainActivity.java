package com.skyeward.tvrelay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 打开即监听，返回即退出。不设 Service，不常驻，不落盘。
 *
 * <p>收到的 APK 数据直接流进系统安装会话，由系统自己在 /data/app-staging 里管理，
 * 因此本应用没有任何"安装包文件"需要删除。
 */
public class MainActivity extends Activity implements TinyHttp.Sink {

    private static final int PORT = 8080;
    private static final String STATUS_ACTION = "com.skyeward.tvrelay.ACTION_INSTALL_STATUS";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TinyHttp server;
    private Thread worker;

    // HTTP 线程写（open/done），UI 线程读（onDestroy）——必须 volatile
    private volatile PackageInstaller.Session session;
    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        TextView url = new TextView(this);
        url.setText("http://" + lanIp() + ":" + PORT);
        url.setTextColor(0xFF7FE3C0);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        url.setGravity(Gravity.CENTER);

        status = new TextView(this);
        // 没拿到"安装未知应用"授权时，createSession/commit 不会弹任何安装界面，
        // 手机上却照样显示"发送完成"——用户只能对着没反应的电视干瞪眼。
        // 与其猜，不如开屏就把真实原因摆在最显眼的地方。
        status.setText(getPackageManager().canRequestPackageInstalls()
                ? "手机浏览器打开上面的网址，选 APK 发送"
                : "未授权安装：先到电视【设置 → 安全与限制 → 安装未知应用】里允许【传APK】，"
                  + "否则传完不会弹出安装界面");
        status.setTextColor(0xFFD0D0D0);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 32, 0, 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101014);
        root.addView(url);
        root.addView(status);
        setContentView(root);

        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                status.setText(st == PackageInstaller.STATUS_SUCCESS
                        ? "安装成功" : "安装失败：" + msg);
            }
        };
        IntentFilter sf = new IntentFilter(STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, sf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, sf);
        }

        server = new TinyHttp(PORT, this);
        worker = new Thread(server, "httpd");
        worker.start();
    }

    @Override
    protected void onDestroy() {
        // accept() 阻塞只能靠关 socket 解开，interrupt 打不断
        if (server != null) {
            server.stop();
        }
        if (worker != null) {
            worker.interrupt();
        }
        if (statusReceiver != null) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Exception ignored) {
            }
        }
        abandonSession();
        super.onDestroy();
    }

    // ---- TinyHttp.Sink：下面两个方法在 HTTP 工作线程上被调用 ----

    @Override
    public OutputStream open(long size) throws IOException {
        abandonSession();
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            // 告诉系统这份包多大：预占安装空间，也让 openWrite 的长度校验对得上
            params.setSize(size);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            return session.openWrite("base.apk", 0, size);
        } catch (Exception e) {
            final String why = e.getClass().getSimpleName() + ": " + e.getMessage();
            ui.post(() -> status.setText("无法创建安装会话：" + why));
            throw new IOException(e);
        }
    }

    @Override
    public void done(boolean ok) {
        PackageInstaller.Session s = session;
        session = null;
        if (s == null) {
            return;
        }
        if (!ok) {
            try {
                s.abandon();
            } catch (Exception ignored) {
            }
            ui.post(() -> status.setText("接收失败，已放弃本次安装"));
            return;
        }
        try {
            Intent intent = new Intent(STATUS_ACTION);
            // 必须同时带 UPDATE_CURRENT：单独传未知的 MUTABLE 位在旧版本上可能被拒
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pi.getIntentSender());
            ui.post(() -> status.setText("已提交安装，请在电视上确认"));
        } catch (Exception e) {
            try {
                s.abandon();
            } catch (Exception ignored) {
            }
            final String why = e.getClass().getSimpleName() + ": " + e.getMessage();
            ui.post(() -> status.setText("提交安装失败：" + why));
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 释放还没提交的安装会话，避免系统侧暂存配额泄漏。 */
    private void abandonSession() {
        PackageInstaller.Session s = session;
        if (s != null) {
            try {
                s.abandon();
            } catch (Exception ignored) {
            }
            session = null;
        }
    }

    /** 取第一个可用的局域网 IPv4；跳过回环、链路本地、IPv6 和点对点网卡。 */
    private static String lanIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName();
                if (name.startsWith("p2p") || name.startsWith("rmnet")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }
}
