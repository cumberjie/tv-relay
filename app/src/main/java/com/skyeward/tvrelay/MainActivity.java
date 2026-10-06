package com.skyeward.tvrelay;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 打开即监听，返回即退出。不设 Service，不常驻。
 */
public class MainActivity extends Activity {

    private static final int PORT = 8080;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private volatile boolean destroyed;
    private TinyHttp server;
    private Thread worker;
    private BroadcastReceiver installDone;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        TextView url = new TextView(this);
        url.setText("http://" + lanIp() + ":" + PORT);
        url.setTextColor(0xFF7FE3C0);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        url.setGravity(Gravity.CENTER);

        status = new TextView(this);
        status.setText("手机浏览器打开上面的网址，选 APK 发送");
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

        // 装失败后不用把包重传一遍：遥控器选中这个按钮按一下，最近收到的那个包会再交给安装器。
        // 包只活到退出 App 为止（退出即清理），所以这个按钮只在本次打开期间有用。
        Button reinstall = new Button(this);
        reinstall.setText("重新安装上一个包");
        reinstall.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        reinstall.setOnClickListener(v -> reinstallLast());
        root.addView(reinstall);
        setContentView(root);

        installDone = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 这里绝对不能删收到的 APK：这条广播收的是"任何" App 的安装完成
                // （代码里拿不到目标包名），而安装确认页可能正停在电视上等你按确认。
                // 删早了，你按确认只会看到"解析软件包时出现问题"。
                // 清理统一由退出 App 时的 TinyHttp.stop() 负责。
                status.setText("检测到安装完成");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        // 少这一行，安装完成的广播永远收不到
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installDone, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installDone, filter);
        }

        server = new TinyHttp(PORT, getCacheDir(),
                file -> ui.post(() -> install(file, "接收完成")),
                () -> ui.post(this::listenFailed));
        worker = new Thread(server, "httpd");
        worker.start();
    }

    @Override
    protected void onDestroy() {
        // 退出后迟到的接收回调一律不理会（服务器线程可能刚 post 了一个安装任务）
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        // accept() 阻塞只能靠关 socket 解开，interrupt 打不断
        if (server != null) {
            server.stop();   // 顺带关掉正在上传的连接、删掉本次收到的文件
        }
        if (worker != null) {
            worker.interrupt();
        }
        if (installDone != null) {
            try {
                unregisterReceiver(installDone);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    /** 弹安装器。file 是这次上传自己的文件，不是"最新那份"；action 用来说明这次为什么装。 */
    private void install(File apk, String action) {
        if (destroyed) {
            return;
        }
        status.setText(action + "，正在打开安装器…");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        // 必须 setDataAndType：分开调 setData/setType 会互相清空
        intent.setDataAndType(
                Uri.parse("content://" + getPackageName() + ".apk/" + apk.getName()),
                "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            status.setText("打开安装器失败：" + e.getMessage());
        }
    }

    /** 「重新安装上一个包」：把最近收到的那个包再交给安装器，省得在手机上重传一遍。 */
    private void reinstallLast() {
        if (destroyed) {
            return;
        }
        File apk = server == null ? null : server.lastReceived();
        if (apk == null || !apk.exists()) {
            status.setText("还没收到过安装包（或上次的包已随退出清理）：先用手机传一个");
            return;
        }
        install(apk, "重装上一个包");
    }

    /** 8080 绑不上（上一个实例没退干净、或端口被别的 App 占着）：必须说出来，
     *  否则界面照常显示网址，手机怎么连都连不上，完全无从排查。 */
    private void listenFailed() {
        status.setText("启动失败：8080 端口被占用，手机连不上。\n请按遥控器返回键退出 App，再重新打开。");
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
