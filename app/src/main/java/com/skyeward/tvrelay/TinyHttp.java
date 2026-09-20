package com.skyeward.tvrelay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 极简 HTTP 服务器，只认两个请求：
 * <pre>
 *   GET /         返回上传页
 *   PUT /upload   把请求体原样写入 dest
 * </pre>
 * 上传走"裸 body"，不用 multipart，服务端因此不需要解析任何边界。
 */
public final class TinyHttp implements Runnable {

    private final int port;
    private final File dest;
    private final Runnable onReceived;

    private volatile ServerSocket server;

    public TinyHttp(int port, File dest, Runnable onReceived) {
        this.port = port;
        this.dest = dest;
        this.onReceived = onReceived;
    }

    @Override
    public void run() {
        try {
            // 必须绑 0.0.0.0，否则只能本机访问
            server = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
            while (true) {
                Socket socket = server.accept();
                try {
                    handle(socket);
                } catch (IOException ignored) {
                    // 单条连接出错不影响后续接收
                } finally {
                    close(socket);
                }
            }
        } catch (IOException stopped) {
            // stop() 关掉 ServerSocket 后 accept 抛异常退出，属正常路径
        }
    }

    public void stop() {
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
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
            return;
        }

        long length = 0;
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                try {
                    length = Long.parseLong(header.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if ("GET".equals(req[0])) {
            byte[] page = PAGE.getBytes("UTF-8");
            header(out, "200 OK", "Content-Type: text/html; charset=utf-8", page.length);
            out.write(page);
            out.flush();
        } else if ("PUT".equals(req[0]) && length > 0) {
            receive(in, length);
            header(out, "200 OK", null, 2);
            out.write("OK".getBytes("ISO-8859-1"));
            out.flush();
            onReceived.run();
        } else {
            header(out, "404 Not Found", null, 0);
            out.flush();
        }
    }

    private void receive(InputStream in, long length) throws IOException {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("无法清理旧文件: " + dest);
        }
        FileOutputStream fos = new FileOutputStream(dest);
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
        } finally {
            fos.close();
        }
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

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
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
            + "#s{color:#9fe1cb;min-height:1.5em}"
            + "</style>"
            + "<h3>传 APK 到电视</h3>"
            + "<input type=file id=f>"
            + "<button onclick=go()>发送并安装</button>"
            + "<p id=s></p>"
            + "<script>"
            + "function go(){"
            + "var f=document.getElementById('f').files[0],s=document.getElementById('s');"
            + "if(!f){s.textContent='请先选择 APK 文件';return;}"
            + "var x=new XMLHttpRequest();x.open('PUT','/upload');"
            + "x.upload.onprogress=function(e){s.textContent='已发送 '+Math.round(e.loaded/e.total*100)+'%';};"
            + "x.onload=function(){s.textContent='发送完成，请在电视上用遥控器确认安装';};"
            + "x.onerror=function(){s.textContent='发送失败，请重试';};"
            + "x.send(f);"
            + "}"
            + "</script>";
}
