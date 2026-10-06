package com.skyeward.tvrelay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 极简 HTTP 服务器，只认两个请求：
 * <pre>
 *   GET /         返回上传页
 *   PUT /upload   把请求体原样写进 directory 下的一个新文件
 * </pre>
 * 上传走"裸 body"，不用 multipart，服务端因此不需要解析任何边界。
 * 每次上传各存一个独立文件（received-随机数.apk）：装 A 的时候手机又传了 B，
 * 两份文件互不覆盖，安装器读到的永远是当初那一份。
 * 上一次运行若是被系统直接杀掉（没走 onDestroy 的清理），它收到的包会留在缓存目录里；
 * 本实例一绑上端口就把这些没人认领的 received-* 清掉，不让残留赖着不走。
 */
public final class TinyHttp implements Runnable {

    /** 单次上传上限 512 MiB：再大就直接 413，别把电视缓存写满。 */
    static final long MAX_UPLOAD = 512L * 1024 * 1024;
    /** 写盘前至少留这么多可用空间，不够就 507。 */
    static final long RESERVE = 32L * 1024 * 1024;

    private final int port;
    private final File directory;
    private final Consumer<File> onReceived;
    private final Runnable onListenFailed;

    private volatile ServerSocket server;
    /** stop() 可能跑在 run() 绑端口之前，那时 server 还是 null；只能靠这面旗子让线程自己收摊。 */
    private volatile boolean stopped;
    /** 正在处理的那条连接：stop() 要把它也关掉，卡在 read() 里的线程才会立刻解开。 */
    private volatile Socket active;
    /** 本实例产生的文件（含写到一半的）：退出时只清理这些，不动别的实例留下的东西。
     *  上次被系统杀掉的那个实例留下的文件不在这里，由启动时的 sweepOrphans() 收拾。 */
    private final CopyOnWriteArrayList<File> owned = new CopyOnWriteArrayList<>();

    public TinyHttp(int port, File directory, Consumer<File> onReceived, Runnable onListenFailed) {
        this.port = port;
        this.directory = directory;
        this.onReceived = onReceived;
        this.onListenFailed = onListenFailed;
    }

    @Override
    public void run() {
        try {
            // 必须绑 0.0.0.0，否则只能本机访问
            ServerSocket bound = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
            server = bound;
            if (stopped) {
                // stop() 早于绑定：这里得自己收摊，否则 8080 被一个已经没人管的线程长期占住，
                // 下次再打开 App 会 BindException，界面照常显示网址但根本没人监听。
                // 这条路径不算启动失败，不要回调界面报"端口被占用"。
                return;
            }
            // 上一次运行被系统杀掉时留下的包在这里先清掉：新实例的名单认识不了它们
            sweepOrphans();
            while (true) {
                Socket socket = bound.accept();
                active = socket;
                if (stopped) {
                    // accept() 返回和 stop() 之间抢了一拍：连接的登记晚于关服务器，自己补一刀。
                    close(socket);
                    return;
                }
                // 读没有超时：一条连上却不发数据的连接（浏览器预连接、Wi-Fi 半开连接）
                // 就能让这个唯一的处理线程永远阻塞，服务器从此不再响应任何上传。
                socket.setSoTimeout(30000);
                try {
                    handle(socket);
                } catch (Exception ignored) {
                    // 单条连接出错不影响后续接收；stop() 关掉连接也走这里。
                    // 这里必须 catch Exception 而不是 IOException：权限类异常是未受检的，
                    // 漏出去会直接打死这个线程，服务器从此不再响应。
                } finally {
                    active = null;
                    close(socket);
                }
            }
        } catch (IOException e) {
            // accept 抛异常退出属正常路径（stop() 关掉了 ServerSocket），
            // 但「绑端口就失败」必须区分出来报给界面：否则界面照常显示网址，
            // 手机却怎么连都连不上，用户完全无从排查。
            // 参数名不能叫 stopped——那会遮蔽下面这个被 stop() 置位的旗子。
            if (!stopped) {
                onListenFailed.run();
            }
        } finally {
            close(server);
        }
    }

    /** 关掉监听和正在处理的那条连接，并删掉本实例接收的文件。用户按返回键退出时调用。 */
    public void stop() {
        stopped = true;
        close(server);
        close(active);
        for (File file : owned) {
            file.delete();
        }
        owned.clear();
    }

    /** 最近一次收完的文件；stop() 之后返回 null。界面上的「重新安装上一个包」用它。 */
    public File lastReceived() {
        return owned.isEmpty() ? null : owned.get(owned.size() - 1);
    }

    /**
     * 清掉上一次运行留下的孤儿文件（received-*.apk / received-*.part）。
     * 上一次若是被系统直接杀掉（不走 onDestroy），它收到的包就留在缓存里，而新开的实例
     * 手里是一份空名单，永远认不出这些东西，残留只能靠系统清缓存带走。
     * 绑上端口＝确定没有第二个实例在跑，此刻清掉最安全：目录里这些文件全是没人认领的，
     * 也不会碰到正在上传或正等着安装的那一份。
     */
    private void sweepOrphans() {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            // 只认自己生成的文件名；目录里别的文件一律不动
            if (name.startsWith("received-") && (name.endsWith(".apk") || name.endsWith(".part"))) {
                file.delete();
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream(), 65536);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4096);

        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        String[] req = parseRequestLine(requestLine);
        if (req == null) {
            respond(out, "400 Bad Request");
            return;
        }
        String method = req[0];
        String path = req[1];

        long length = -1;
        boolean repeatedLength = false;
        boolean chunked = false;
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                if (length >= 0) {
                    repeatedLength = true;
                }
                length = parseLength(header.substring(15));
            } else if (header.regionMatches(true, 0, "Transfer-Encoding:", 0, 18)) {
                chunked = true;
            }
        }

        if ("GET".equals(method)) {
            if (!"/".equals(path)) {
                respond(out, "404 Not Found");
                return;
            }
            byte[] page = PAGE.getBytes("UTF-8");
            header(out, "200 OK", "Content-Type: text/html; charset=utf-8", page.length);
            out.write(page);
            out.flush();
            return;
        }
        if (!"PUT".equals(method) || !"/upload".equals(path)) {
            respond(out, "404 Not Found");
            return;
        }
        if (length <= 0 || repeatedLength || chunked) {
            // 没写长度、长度非法、长度写了两遍、又声明 chunked：一律当坏请求。
            // 宁可让手机重传，也不猜一个大小就开始写文件。
            respond(out, "400 Bad Request");
            return;
        }
        if (length > MAX_UPLOAD) {
            respond(out, "413 Payload Too Large");
            return;
        }
        if (directory.getUsableSpace() < length + RESERVE) {
            respond(out, "507 Insufficient Storage");
            return;
        }
        File apk;
        try {
            apk = receive(in, length);
        } catch (IOException truncated) {
            // 手机断网/锁屏/点了取消：body 没传完。半成品已经在 receive() 里删掉，
            // 这里回失败，手机页面不会再显示"发送完成"，也不会通知安装器。
            respond(out, "400 Bad Request");
            return;
        }
        if (stopped) {
            // 收完的瞬间用户退出了 App：不留文件、不弹安装器。
            apk.delete();
            return;
        }
        respond(out, "200 OK");
        onReceived.accept(apk);
    }

    /**
     * 把 body 写进目录下的临时文件，收满 length 个字节才改名成 .apk 返回。
     * 中途断了、超时了、改名失败，都会把半成品删掉——残缺的 APK 绝不流到安装器。
     */
    private File receive(InputStream in, long length) throws IOException {
        File part = File.createTempFile("received-", ".part", directory);
        boolean completed = false;
        try {
            FileOutputStream fos = new FileOutputStream(part);
            try {
                byte[] buf = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) {
                        break;
                    }
                    fos.write(buf, 0, n);
                    remaining -= n;
                }
                if (remaining > 0) {
                    // 抛出后 onReceived 不执行，也就不会把残缺的 APK 交给安装器
                    // （用户确认后只会看到"解析包错误"）。
                    throw new IOException("body truncated, " + remaining + " of " + length + " bytes missing");
                }
            } finally {
                fos.close();
            }
            String name = part.getName();
            File apk = new File(directory, name.substring(0, name.length() - ".part".length()) + ".apk");
            if (!part.renameTo(apk)) {
                throw new IOException("改名失败: " + part);
            }
            completed = true;
            owned.add(apk);
            return apk;
        } finally {
            if (!completed) {
                part.delete();
            }
        }
    }

    /** 只认纯十进制数字的 Content-Length；+1、-1、空串、溢出都返回 -1（非法）。 */
    private static long parseLength(String value) {
        String text = value.trim();
        if (text.isEmpty() || text.length() > 18) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException tooBig) {
            return -1;
        }
    }

    private static void respond(OutputStream out, String status) throws IOException {
        header(out, status, null, 0);
        out.flush();
    }

    private static void header(OutputStream out, String status, String extra, int length)
            throws IOException {
        StringBuilder sb = new StringBuilder(96);
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        if (extra != null) {
            sb.append(extra).append("\r\n");
        }
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    /**
     * 逐字节读到换行为止。绝不能用 BufferedReader.readLine()——它会预读缓冲，
     * 把紧随其后的二进制 body 一起吞掉，APK 就传坏了。
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int c = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), "ISO-8859-1");
    }

    /** 拆请求行，返回 {method, path}；无法解析时返回 null。 */
    static String[] parseRequestLine(String line) {
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return null;
        }
        return new String[] { parts[0], parts[1] };
    }

    private static void close(Closeable target) {
        if (target != null) {
            try {
                target.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final String PAGE =
            "<!doctype html><meta charset=utf-8>"
            + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
            + "<title>传 APK 到电视</title>"
            + "<style>"
            + "body{font-family:sans-serif;margin:1.5em;background:#101014;color:#e8e8e8}"
            + "h3{font-weight:500}"
            + "input,button{font-size:1.05em;padding:.7em;margin:.4em 0;width:100%;box-sizing:border-box;border-radius:8px;border:1px solid #333;background:#1b1b20;color:#e8e8e8}"
            + "button{background:#0f6e56;border:0;color:#fff}"
            + "button:disabled{background:#2a3b36;color:#8a8a8a}"
            + "#s{color:#9fe1cb;min-height:1.5em}"
            + "</style>"
            + "<h3>传 APK 到电视</h3>"
            + "<input type=file id=f>"
            + "<button id=b onclick=go()>发送并安装</button>"
            + "<p id=s></p>"
            + "<script>"
            + "function go(){"
            + "var f=document.getElementById('f').files[0],s=document.getElementById('s'),b=document.getElementById('b');"
            + "if(!f){s.textContent='请先选择 APK 文件';return;}"
            + "var x=new XMLHttpRequest();x.open('PUT','/upload');"
            + "x.upload.onprogress=function(e){s.textContent='已发送 '+Math.round(e.loaded/e.total*100)+'%';};"
            + "x.onload=function(){"
            + "b.disabled=false;"
            + "s.textContent=x.status>=200&&x.status<300?'发送完成，请在电视上用遥控器确认安装':'发送失败（电视返回 '+x.status+'），请重试';"
            + "};"
            + "x.onerror=function(){b.disabled=false;s.textContent='发送失败，请重试';};"
            + "b.disabled=true;"
            + "x.send(f);"
            + "}"
            + "</script>";
}
