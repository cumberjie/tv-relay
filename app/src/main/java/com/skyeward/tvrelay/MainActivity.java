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
    private File temp;
    private TinyHttp server;
    private Thread worker;
    private BroadcastReceiver installDone;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        temp = new File(getCacheDir(), "received.apk");

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
        setContentView(root);

        installDone = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 这里绝对不能删 received.apk：这条广播收的是"任何" App 的安装完成
                // （代码里拿不到目标包名），而安装确认页可能正停在电视上等你按确认。
                // 删早了，你按确认只会看到"解析软件包时出现问题"。
                // 清理改由 onDestroy 和 TinyHttp.receive() 开头负责。
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

        server = new TinyHttp(PORT, temp, () -> ui.post(this::install),
                () -> ui.post(this::listenFailed));
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
        if (installDone != null) {
            try {
                unregisterReceiver(installDone);
            } catch (Exception ignored) {
            }
        }
        deleteTemp();
        super.onDestroy();
    }

    private void install() {
        status.setText("接收完成，正在打开安装器…");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        // 必须 setDataAndType：分开调 setData/setType 会互相清空
        intent.setDataAndType(
                Uri.parse("content://" + getPackageName() + ".apk/received.apk"),
                "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            status.setText("打开安装器失败：" + e.getMessage());
        }
    }

    /** 8080 绑不上（上一个实例没退干净、或端口被别的 App 占着）：必须说出来，
     *  否则界面照常显示网址，手机怎么连都连不上，完全无从排查。 */
    private void listenFailed() {
        status.setText("启动失败：8080 端口被占用，手机连不上。\n请按遥控器返回键退出 App，再重新打开。");
    }

    private void deleteTemp() {
        if (temp != null && temp.exists()) {
            temp.delete();
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
