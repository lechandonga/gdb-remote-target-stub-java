package com.github.lechandonga.gdbremotetargetstubjava.gdb;

import com.github.lechandonga.gdbremotetargetstubjava.target.TargetMachine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP front-end of the stub. Exactly one debug session may be active at a
 * time; extra connections are rejected with a plain-text reason and closed.
 * After a disconnect a new session may attach: the target (memory, registers,
 * breakpoints) is shared, so target state persists across sessions.
 */
public class GdbStubServer implements AutoCloseable {

    public static final String BUSY_MESSAGE = "E:stub busy, another debug session is active\r\n";

    private final ServerSocket serverSocket;
    private final TargetMachine target;
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private final Thread acceptThread;
    private volatile boolean closed = false;

    public GdbStubServer(int port) throws IOException {
        this(new TargetMachine(), port);
    }

    public GdbStubServer(TargetMachine target, int port) throws IOException {
        this.target = target;
        this.serverSocket = new ServerSocket(port);
        this.acceptThread = new Thread(this::acceptLoop, "gdb-stub-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public TargetMachine getTarget() {
        return target;
    }

    private void acceptLoop() {
        GdbSession.log("listening on port " + getPort());
        while (!closed) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    GdbSession.log("accept failed: " + e.getMessage());
                }
                return;
            }
            if (!sessionActive.compareAndSet(false, true)) {
                GdbSession.log("rejecting extra connection from " + socket.getRemoteSocketAddress());
                try {
                    OutputStream out = socket.getOutputStream();
                    out.write(BUSY_MESSAGE.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    socket.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            Thread t = new Thread(() -> {
                try {
                    new GdbSession(socket, target).run();
                } catch (IOException e) {
                    GdbSession.log("session setup failed: " + e.getMessage());
                } finally {
                    sessionActive.set(false);
                }
            }, "gdb-stub-session");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
    }
}
