package com.github.lechandonga.gdbremotetargetstubjava.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 本地回环上的假 GDB 客户端：手工实现协议封帧，以便精确制造损坏包、
 * 重传、0x03 中断、无确认模式等场景。
 */
public class FakeGdbClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private boolean noAck;

    public FakeGdbClient(int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public void setNoAck(boolean noAck) {
        this.noAck = noAck;
    }

    /** 发送一个数据包并（确认模式下）读取 stub 的 +；返回完整线上帧。 */
    public byte[] sendPacket(String payload) throws IOException {
        byte[] frame = frame(payload.getBytes(StandardCharsets.ISO_8859_1));
        out.write(frame);
        out.flush();
        if (!noAck) {
            int ack = in.read();
            if (ack != '+') {
                throw new IOException("expected '+' ack, got " + (char) ack);
            }
        }
        return frame;
    }

    /** 发送任意原始字节（用于制造损坏帧/噪声）。 */
    public void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    public static byte[] frameOf(String payload) {
        return frame(payload.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** X 包专用：ASCII 头不转义、二进制数据段逐字节转义，整体计算校验和。 */
    public static byte[] frameWithBinaryHeader(String asciiHeader, byte[] data) {
        byte[] header = asciiHeader.getBytes(StandardCharsets.ISO_8859_1);
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(header);
        java.io.ByteArrayOutputStream escaped = new java.io.ByteArrayOutputStream();
        for (byte rawByte : data) {
            int value = rawByte & 0xff;
            if (value == '#' || value == '$' || value == '}' || value == '*'
                    || value == 0x03 || value == 0x7d || value == 0x7f) {
                escaped.write(0x7d);
                escaped.write(value ^ 0x20);
            } else {
                escaped.write(value);
            }
        }
        byte[] escapedBytes = escaped.toByteArray();
        int sum = 0;
        for (byte headerByte : header) {
            sum = (sum + (headerByte & 0xff)) & 0xff;
        }
        for (byte escapedByte : escapedBytes) {
            sum = (sum + (escapedByte & 0xff)) & 0xff;
        }
        body.writeBytes(escapedBytes);
        body.write('#');
        body.write(hex((sum >>> 4) & 0xf));
        body.write(hex(sum & 0xf));
        byte[] bodyBytes = body.toByteArray();
        byte[] framed = new byte[bodyBytes.length + 1];
        framed[0] = '$';
        System.arraycopy(bodyBytes, 0, framed, 1, bodyBytes.length);
        return framed;
    }

    public int readRawByte() throws IOException {
        socket.setSoTimeout(1000);
        return in.read();
    }

    public void sendAck() throws IOException {
        out.write('+');
        out.flush();
    }

    public byte[] receivePacketBytesNoAck() throws IOException {
        int b;
        do {
            b = in.read();
        } while (b != '$');
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        while ((b = in.read()) != '#') {
            payload.write(b);
        }
        in.read();
        in.read();
        return unescape(payload.toByteArray());
    }

    public void sendInterrupt() throws IOException {
        out.write(0x03);
        out.flush();
    }

    /** 用给定的两位十六进制校验和发送一个损坏包（确认模式下应收到 '-'）。 */
    public void sendPacketWithBadChecksum(String payload) throws IOException {
        byte[] good = frame(payload.getBytes(StandardCharsets.ISO_8859_1));
        // 强制改成与原值不同的合法十六进制字符，保证校验和必然不同。
        int original = good[good.length - 1];
        good[good.length - 1] = (byte) (original == '0' ? '1' : '0');
        out.write(good);
        out.flush();
    }

    /** 收一个数据包，自动发出 +（确认模式）。返回去帧去校验后的 payload。 */
    public String receivePacket() throws IOException {
        return new String(receivePacketBytes(), StandardCharsets.ISO_8859_1);
    }

    public byte[] receivePacketBytes() throws IOException {
        int b;
        do {
            b = in.read();
            if (b < 0) {
                throw new IOException("connection closed while waiting for '$'");
            }
        } while (b != '$');

        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        while ((b = in.read()) != '#') {
            if (b < 0) {
                throw new IOException("connection closed inside packet");
            }
            payload.write(b);
        }
        int c1 = in.read();
        int c2 = in.read();
        if (!noAck) {
            out.write('+');
            out.flush();
        }
        byte[] framed = payload.toByteArray();
        int expected = Integer.parseInt(new String(new char[]{(char) c1, (char) c2}), 16);
        int actual = checksum(framed);
        if (expected != actual) {
            throw new IOException(String.format(
                    "received packet with bad checksum expected=%02x actual=%02x",
                    expected, actual));
        }
        return unescape(framed);
    }

    public String receiveRawLine() throws IOException {
        socket.setSoTimeout(2000);
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    public void expectClosed() throws IOException {
        socket.setSoTimeout(2000);
        int b = in.read();
        if (b >= 0) {
            throw new IOException("expected EOF, got byte " + b);
        }
    }

    private static byte[] frame(byte[] payload) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write('$');
        int sum = 0;
        for (byte rawByte : payload) {
            int value = rawByte & 0xff;
            sum = (sum + value) & 0xff;
            if (value == '#' || value == '$' || value == '}' || value == '*'
                    || value == 0x03 || value == 0x7d || value == 0x7f) {
                o.write(0x7d);
                o.write(value ^ 0x20);
            } else {
                o.write(value);
            }
        }
        o.write('#');
        o.write(hex((sum >>> 4) & 0xf));
        o.write(hex(sum & 0xf));
        return o.toByteArray();
    }

    private static int hex(int nibble) {
        return nibble < 10 ? '0' + nibble : 'a' + (nibble - 10);
    }

    private static int checksum(byte[] framedPayload) {
        int sum = 0;
        for (byte b : framedPayload) {
            sum += b & 0xff;
        }
        return sum & 0xff;
    }

    private static byte[] unescape(byte[] framed) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < framed.length; i++) {
            int b = framed[i] & 0xff;
            if (b == 0x7d && i + 1 < framed.length) {
                o.write((framed[++i] & 0xff) ^ 0x20);
            } else {
                o.write(b);
            }
        }
        return o.toByteArray();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
