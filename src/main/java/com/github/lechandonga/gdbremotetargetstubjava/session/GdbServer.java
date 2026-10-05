package com.github.lechandonga.gdbremotetargetstubjava.session;

import com.github.lechandonga.gdbremotetargetstubjava.target.SimBoard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP 服务器：同一时间只允许一个调试会话。
 *
 * <p>多余连接不会进入协议状态机，而是收到一行可机读的拒绝说明
 * {@code ERR:E80:debug session already in use from <peer>} 后被关闭，
 * 避免挂死或污染正在进行的会话。会话断开后槽位立即释放，允许重连；
 * 模拟板子的内存、寄存器、断点在重连之间保留（{@code k}/{@code R} 才复位）。
 */
@Component
public class GdbServer {

    private static final Logger log = LoggerFactory.getLogger(GdbServer.class);
    public static final int REJECT_SESSION_BUSY = 0x80;

    private final SimBoard board = new SimBoard();
    private final AtomicReference<Socket> active = new AtomicReference<>();
    private final ExecutorService sessionExecutors = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gdb-session");
        t.setDaemon(true);
        return t;
    });

    @Value("${gdbstub.port:2345}")
    private int configuredPort;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile int boundPort;

    @PostConstruct
    public void start() throws IOException {
        serverSocket = new ServerSocket(configuredPort);
        boundPort = serverSocket.getLocalPort();
        acceptThread = new Thread(this::acceptLoop, "gdb-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("GDB remote target stub listening on port {} (single session)", boundPort);
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                if (active.compareAndSet(null, socket)) {
                    log.info("debug session accepted from {}", socket.getRemoteSocketAddress());
                    GdbSession session = new GdbSession(socket, board,
                            () -> release(socket));
                    sessionExecutors.submit(session);
                } else {
                    reject(socket);
                }
            } catch (IOException e) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    log.warn("accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void reject(Socket socket) {
        Socket current = active.get();
        String peer = current == null ? "unknown" : String.valueOf(current.getRemoteSocketAddress());
        String message = "ERR:E" + Integer.toHexString(REJECT_SESSION_BUSY)
                + ":debug session already in use from " + peer + "\n";
        try {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(message.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            log.debug("could not send busy rejection: {}", e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void release(Socket socket) {
        if (active.compareAndSet(socket, null)) {
            log.info("debug session released, reconnect permitted");
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    public SimBoard getBoard() {
        return board;
    }

    @PreDestroy
    public void stop() {
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        sessionExecutors.shutdownNow();
    }
}
