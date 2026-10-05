package com.github.lechandonga.gdbremotetargetstubjava.session;

import com.github.lechandonga.gdbremotetargetstubjava.protocol.PacketConnection;
import com.github.lechandonga.gdbremotetargetstubjava.target.SimBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;

/**
 * 一个已被接受的调试会话：单线程顺序处理命令包，0x03 中断通过读线程回调打断运行。
 */
public class GdbSession implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GdbSession.class);

    private final Socket socket;
    private final SimBoard board;
    private final Runnable onClose;
    private PacketConnection connection;

    public GdbSession(Socket socket, SimBoard board, Runnable onClose) {
        this.socket = socket;
        this.board = board;
        this.onClose = onClose;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            connection = new PacketConnection(socket.getInputStream(), socket.getOutputStream());
            connection.setInterruptListener(board::requestInterrupt);
            connection.start();
            CommandHandler handler = new CommandHandler(board, connection);
            while (true) {
                byte[] packet = connection.nextPacket();
                if (packet.length == 0) {
                    break; // EOF 哨兵
                }
                String incoming = new String(packet,
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                if (incoming.startsWith("D")) {
                    connection.send("OK".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    break; // detach：按协议回复后关闭 TCP 连接
                }
                byte[] reply = handler.handle(packet);
                if (reply == null) {
                    break; // k（kill）：复位后直接关闭连接
                }
                connection.send(reply);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("session ended with IO error: {}", e.getMessage());
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        onClose.run();
    }
}
