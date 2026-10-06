package com.skyeward.tvrelay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 走真实本地 socket，不 mock：这些用例盯的是复审发现的问题——
 * 退出时连接不断开、多次上传共用一个文件、非法请求静默成功、残缺上传留垃圾。
 */
public class TinyHttpTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File directory;
    private int port;
    private TinyHttp server;
    private Thread worker;
    private final BlockingQueue<File> received = new LinkedBlockingQueue<>();
    private final CountDownLatch listenFailed = new CountDownLatch(1);

    @Before
    public void startServer() throws Exception {
        port = freePort();
        start(temp.newFolder("uploads"));
        assertStatus("200 OK", request("GET / HTTP/1.1\r\n\r\n"));
    }

    @After
    public void stopServer() throws Exception {
        server.stop();
        worker.join(3000);
        assertFalse("HTTP worker must exit after stop()", worker.isAlive());
    }

    private void start(File uploadDirectory) {
        directory = uploadDirectory;
        server = new TinyHttp(port, uploadDirectory, received::add, listenFailed::countDown);
        worker = new Thread(server, "test-httpd");
        worker.start();
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    public void parsesPutUpload() {
        assertArrayEquals(new String[] { "PUT", "/upload" },
                TinyHttp.parseRequestLine("PUT /upload HTTP/1.1"));
    }

    @Test
    public void parsesGetRoot() {
        assertArrayEquals(new String[] { "GET", "/" },
                TinyHttp.parseRequestLine("GET / HTTP/1.1"));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(TinyHttp.parseRequestLine("bogus"));
    }

    @Test
    public void receivesExactBinaryBody() throws Exception {
        byte[] body = new byte[] { 0, 13, 10, (byte) 255, 42 };
        try (Socket socket = connect()) {
            socket.getOutputStream().write(
                    "PUT /upload HTTP/1.1\r\nContent-Length: 5\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(body);
            socket.shutdownOutput();
            assertStatus("200 OK", readResponse(socket));
        }
        File apk = takeApk();
        assertTrue("收到的一定是 .apk 文件：" + apk, apk.getName().endsWith(".apk"));
        assertArrayEquals(body, Files.readAllBytes(apk.toPath()));
    }

    @Test
    public void rejectsUnexpectedPaths() throws Exception {
        assertStatus("404 Not Found", request("GET /other HTTP/1.1\r\n\r\n"));
        assertStatus("404 Not Found",
                request("PUT /other HTTP/1.1\r\nContent-Length: 1\r\n\r\nA"));
        assertEquals(0, received.size());
        assertEquals(0, directory.list().length);
        assertStatus("200 OK", request("GET / HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void rejectsMissingInvalidAndConflictingLengths() throws Exception {
        String[] headers = {
                "",
                "Content-Length: 0\r\n",
                "Content-Length: -1\r\n",
                "Content-Length: +1\r\n",
                "Content-Length: abc\r\n",
                "Content-Length: 9223372036854775808\r\n",
                "Content-Length: 1\r\nContent-Length: 1\r\n",
                "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n"
        };
        for (String header : headers) {
            assertStatus("400 Bad Request",
                    request("PUT /upload HTTP/1.1\r\n" + header + "\r\n"));
        }
        assertEquals(0, received.size());
        assertEquals(0, directory.list().length);
        assertStatus("200 OK", request("GET / HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void rejectsUploadsOver512MiBBeforeCreatingFiles() throws Exception {
        assertStatus("413 Payload Too Large",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 536870913\r\n\r\n"));
        assertEquals(0, received.size());
        assertEquals(0, directory.list().length);
    }

    @Test
    public void reservesFreeSpaceBeforeReceiving() throws Exception {
        server.stop();
        worker.join(3000);
        File almostFull = new File(directory.getPath()) {
            @Override
            public long getUsableSpace() {
                return 32L * 1024 * 1024;
            }
        };
        start(almostFull);
        assertStatus("507 Insufficient Storage",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 1\r\n\r\nA"));
        assertEquals(0, received.size());
        assertEquals(0, directory.list().length);
    }

    @Test
    public void deletesTruncatedUploadAndDoesNotNotifyInstaller() throws Exception {
        assertStatus("400 Bad Request",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 5\r\n\r\nAB"));
        assertEquals(0, received.size());
        assertEquals(0, directory.list().length);
        assertStatus("200 OK", request("GET / HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void doesNotPublishPartialUploadAsApk() throws Exception {
        try (Socket socket = connect()) {
            sendPartial(socket);
            awaitPartialFile();
            assertEquals(0, received.size());
            assertEquals(0, directory.list((dir, name) -> name.endsWith(".apk")).length);
        }
    }

    @Test
    public void keepsEachCompletedUploadIndependent() throws Exception {
        assertStatus("200 OK",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 3\r\n\r\nAAA"));
        File first = takeApk();
        assertStatus("200 OK",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 3\r\n\r\nBBB"));
        File second = takeApk();
        assertNotEquals("两次上传必须是两个文件，装 A 时传 B 不能把 A 覆盖掉", first, second);
        assertArrayEquals(new byte[] { 65, 65, 65 }, Files.readAllBytes(first.toPath()));
        assertArrayEquals(new byte[] { 66, 66, 66 }, Files.readAllBytes(second.toPath()));
    }

    @Test
    public void stopClosesActiveUploadWithoutWaitingForClient() throws Exception {
        try (Socket socket = connect()) {
            sendPartial(socket);
            awaitPartialFile();
            server.stop();
            worker.join(1500);
            assertFalse("stop() 必须解开卡在 read() 上的连接", worker.isAlive());
            assertEquals(0, received.size());
            assertEquals(0, directory.list().length);
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test
    public void doesNotNotifyInstallerAfterStop() throws Exception {
        try (Socket socket = connect()) {
            sendPartial(socket);
            awaitPartialFile();
            server.stop();
            try {
                socket.getOutputStream().write(new byte[] { 66, 67 });
                socket.shutdownOutput();
            } catch (IOException closedByServer) {
                // 退出时服务器关掉这条连接，属预期
            }
            worker.join(1500);
            assertFalse(worker.isAlive());
            assertEquals("退出后不得再排安装任务", 0, received.size());
            assertEquals(0, directory.list().length);
        }
    }

    @Test
    public void stopCleansOnlyThisServersOwnFiles() throws Exception {
        assertStatus("200 OK",
                request("PUT /upload HTTP/1.1\r\nContent-Length: 1\r\n\r\nA"));
        File apk = takeApk();
        File foreign = new File(directory, "received-other-instance.apk");
        Files.write(foreign.toPath(), new byte[] { 42 });
        server.stop();
        worker.join(1500);
        assertFalse(worker.isAlive());
        assertFalse("本实例收到的文件随退出清理", apk.exists());
        assertTrue("stop() 只清本实例收到的文件，这个不是它收的", foreign.exists());
    }

    @Test
    public void stopBeforeRunReleasesPortWithoutFailureCallback() throws Exception {
        server.stop();
        worker.join(3000);
        server = new TinyHttp(port, directory, received::add, listenFailed::countDown);
        server.stop();
        worker = new Thread(server, "already-stopped-httpd");
        worker.start();
        worker.join(1500);
        assertFalse(worker.isAlive());
        assertEquals("stop() 早于绑端口不算启动失败", 1, listenFailed.getCount());
        assertEquals(0, received.size());
        try (ServerSocket rebound = new ServerSocket(port)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test
    public void reportsOccupiedPortAndExits() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        TinyHttp duplicate = new TinyHttp(port, directory, received::add, occupied::countDown);
        Thread duplicateWorker = new Thread(duplicate, "duplicate-httpd");
        duplicateWorker.start();
        try {
            assertTrue("端口被占用必须回调界面", occupied.await(3, TimeUnit.SECONDS));
            duplicateWorker.join(1500);
            assertFalse(duplicateWorker.isAlive());
        } finally {
            duplicate.stop();
        }
    }

    @Test
    public void startupSweepsLeftoversFromAKilledInstance() throws Exception {
        server.stop();
        worker.join(3000);
        File orphanApk = new File(directory, "received-999.apk");
        File orphanPart = new File(directory, "received-999.part");
        File unrelated = new File(directory, "unrelated.txt");
        Files.write(orphanApk.toPath(), new byte[] { 1 });
        Files.write(orphanPart.toPath(), new byte[] { 1 });
        Files.write(unrelated.toPath(), new byte[] { 1 });
        start(directory);
        // 服务器先扫残留、后进 accept 循环：能拿到 200 就说明已经扫完了
        assertStatus("200 OK", request("GET / HTTP/1.1\r\n\r\n"));
        assertFalse("上次被系统杀掉留下的包，下次打开就该清掉", orphanApk.exists());
        assertFalse("写了一半的半成品也不能留", orphanPart.exists());
        assertTrue("只清自己生成的 received-*，别的文件不动", unrelated.exists());
    }

    @Test
    public void keepsLeftoversWhenAnotherInstanceStillHoldsThePort() throws Exception {
        File orphan = new File(directory, "received-777.apk");
        Files.write(orphan.toPath(), new byte[] { 1 });
        CountDownLatch occupied = new CountDownLatch(1);
        TinyHttp second = new TinyHttp(port, directory, received::add, occupied::countDown);
        Thread secondWorker = new Thread(second, "second-httpd");
        secondWorker.start();
        try {
            assertTrue("第二个实例绑不上端口", occupied.await(3, TimeUnit.SECONDS));
            assertTrue("端口还被占着＝还有实例在跑，此刻不许清目录里的包", orphan.exists());
        } finally {
            second.stop();
            secondWorker.join(1500);
        }
    }

    private Socket connect() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try {
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                socket.setSoTimeout(3000);
                return socket;
            } catch (ConnectException notListeningYet) {
                if (System.nanoTime() >= deadline) {
                    throw notListeningYet;
                }
                Thread.sleep(5);
            }
        }
    }

    private String request(String text) throws Exception {
        try (Socket socket = connect()) {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
            socket.shutdownOutput();
            return readResponse(socket);
        }
    }

    private static String readResponse(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bytes.write(buf, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertStatus(String status, String response) {
        assertTrue("期望 " + status + "，实际：" + response,
                response.startsWith("HTTP/1.1 " + status + "\r\n"));
    }

    private File takeApk() throws Exception {
        File apk = received.poll(3, TimeUnit.SECONDS);
        assertNotNull("收满整个文件后才轮到安装回调", apk);
        return apk;
    }

    private static void sendPartial(Socket socket) throws IOException {
        socket.getOutputStream().write(
                "PUT /upload HTTP/1.1\r\nContent-Length: 3\r\n\r\nA".getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    /** 等到写了一半的临时文件出现在目录里，确保 stop() 打的是"正在收"的这条连接。 */
    private void awaitPartialFile() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.length() == 1) {
                        return;
                    }
                }
            }
            assertTrue("服务器没能开始接收 body", System.nanoTime() < deadline);
            Thread.sleep(5);
        }
    }
}
