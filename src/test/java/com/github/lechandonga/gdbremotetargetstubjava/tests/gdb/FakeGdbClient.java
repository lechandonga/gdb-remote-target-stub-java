package com.github.lechandonga.gdbremotetargetstubjava.tests.gdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Loopback fake GDB client with full control over the wire format:
 * raw bytes, hand-built frames (including deliberately corrupted ones),
 * ACK/NAK observation and no-ack mode.
 */
public class FakeGdbClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private boolean noAckMode = false;

    public FakeGdbClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public void setNoAckMode(boolean noAckMode) {
        this.noAckMode = noAckMode;
    }

    /** Send raw bytes exactly as given. */
    public void sendRaw(String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Send one well-formed framed packet. */
    public void sendPacket(String payload) throws IOException {
        int checksum = 0;
        for (char c : payload.toCharArray()) {
            checksum = (checksum + c) & 0xFF;
        }
        sendRaw("$" + payload + "#" + String.format("%02x", checksum));
    }

    /** Send a packet with a deliberately wrong checksum. */
    public void sendPacketBadChecksum(String payload) throws IOException {
        int checksum = 0;
        for (char c : payload.toCharArray()) {
            checksum = (checksum + c) & 0xFF;
        }
        sendRaw("$" + payload + "#" + String.format("%02x", (checksum + 1) & 0xFF));
    }

    /** Read a single raw byte. */
    public int readByte() throws IOException {
        return in.read();
    }

    /** Read one byte and assert it is an ACK ('+'). */
    public void expectAck() throws IOException {
        int b = readByte();
        if (b != '+') {
            throw new AssertionError("expected ACK '+', got " + (b == -1 ? "EOF" : "'" + (char) b + "'"));
        }
    }

    /** Read one byte and assert it is a NAK ('-'). */
    public void expectNak() throws IOException {
        int b = readByte();
        if (b != '-') {
            throw new AssertionError("expected NAK '-', got " + (b == -1 ? "EOF" : "'" + (char) b + "'"));
        }
    }

    /** Read one framed reply, unescape it, verify checksum; consumes the leading ACK if present. */
    public String readReply() throws IOException {
        int b;
        while (true) {
            b = readByte();
            if (b == -1) {
                throw new AssertionError("EOF while waiting for reply");
            }
            if (b == '$') {
                break;
            }
            if (b != '+') {
                throw new AssertionError("unexpected byte while waiting for reply: '" + (char) b + "'");
            }
        }
        StringBuilder payload = new StringBuilder();
        int checksum = 0;
        while (true) {
            b = readByte();
            if (b == -1) {
                throw new AssertionError("EOF inside reply frame");
            }
            if (b == '#') {
                break;
            }
            if (b == 0x7D) {
                b = readByte() ^ 0x20;
            }
            checksum = (checksum + b) & 0xFF;
            payload.append((char) b);
        }
        int hi = readByte();
        int lo = readByte();
        int expected = Integer.parseInt("" + (char) hi + (char) lo, 16);
        if (expected != checksum) {
            throw new AssertionError("reply checksum mismatch for '" + payload + "'");
        }
        return payload.toString();
    }

    /** GDB-style round trip: send packet, expect ACK (unless no-ack), return reply payload. */
    public String command(String payload) throws IOException {
        sendPacket(payload);
        if (!noAckMode) {
            expectAck();
        }
        return readReply();
    }

    /** Send a GDB interrupt (Ctrl-C, 0x03). */
    public void interrupt() throws IOException {
        out.write(0x03);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
