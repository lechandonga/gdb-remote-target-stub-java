package com.github.lechandonga.gdbremotetargetstubjava.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 单条 GDB 远程连接的封帧/确认状态机。
 *
 * <p>读线程持续解析输入：{@code +/-} 确认、0x03 中断、{@code $...#cc} 数据包。
 * 校验错误的数据包会被丢弃并（在确认模式下）回 {@code -} 要求重传；
 * 不完整的输入（没有合法结尾）永远等待后续字节，不污染之后的交互；
 * 起始标记前的噪声字节被静默丢弃。
 *
 * <p>发送端在确认模式下等待 {@code +}，超时重传，最多 3 次；无确认模式直接发送。
 */
public class PacketConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PacketConnection.class);
    private static final int ACK_TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 3;

    private final InputStream in;
    private final OutputStream out;
    private final BlockingQueue<byte[]> packetQueue = new ArrayBlockingQueue<>(256);
    private final BlockingQueue<Byte> ackQueue = new ArrayBlockingQueue<>(16);

    private volatile boolean noAckMode = false;
    private volatile boolean closed;
    private volatile Runnable interruptListener;

    public PacketConnection(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public void start() {
        Thread reader = new Thread(this::readerLoop, "gdb-packet-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void setInterruptListener(Runnable listener) {
        this.interruptListener = listener;
    }

    public boolean isNoAckMode() {
        return noAckMode;
    }

    /** 进入无确认模式：之后收发均不再处理 +/-。 */
    public void enableNoAckMode() {
        this.noAckMode = true;
    }

    /** 读取下一个数据包（payload 已完成反转义），连接断开返回 null。 */
    public byte[] nextPacket() throws InterruptedException {
        return packetQueue.take();
    }

    private void readerLoop() {
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        try {
            int state = 0; // 0: 等待 $ ; 1: 收 payload; 2: 第 1 个校验位; 3: 第 2 个校验位
            int firstNibble = 0;
            while (!closed) {
                int raw = in.read();
                if (raw < 0) {
                    break;
                }
                int b = raw & 0xff;
                if (state == 0) {
                    if (b == '$') {
                        payload.reset();
                        state = 1;
                    } else if (b == '+') {
                        offerAck((byte) '+');
                    } else if (b == '-') {
                        offerAck((byte) '-');
                    } else if (b == 0x03) {
                        Runnable listener = interruptListener;
                        if (listener != null) {
                            listener.run();
                        }
                    }
                    // 其余噪声字节静默丢弃，直到下一个 $
                    continue;
                }
                if (state == 1) {
                    if (b == 0x7d) {
                        // 转义引导符：与下一字节异或 0x20，原样收入（unescape 在收包后做）。
                        int next = in.read();
                        if (next < 0) {
                            break;
                        }
                        payload.write(0x7d);
                        payload.write(next & 0xff);
                        state = 1;
                    } else if (b == '#') {
                        state = 2;
                    } else if (b == '$') {
                        // 未收完又遇到起始标记：按损坏包重新开始，旧内容丢弃。
                        payload.reset();
                        state = 1;
                    } else {
                        payload.write(b);
                    }
                    continue;
                }
                // state == 2/3：两个校验位
                int nibble = hexValue(b);
                if (nibble < 0) {
                    // 校验位损坏：无法完成本包，回同步状态等待新的 $
                    log.debug("packet checksum digit corrupted, resyncing");
                    state = 0;
                    continue;
                }
                if (state == 2) {
                    state = 3;
                    firstNibble = nibble;
                    continue;
                }
                state = 0;
                byte[] framed = payload.toByteArray();
                int expected = (firstNibble << 4) | nibble;
                // 校验和针对线上帧 data 段（转义后字节）计算。
                int actual = PacketCodec.checksum(framed, framed.length);
                if (actual == expected) {
                    if (!noAckMode) {
                        writeRaw(PacketCodec.ACK);
                    }
                    byte[] data = PacketCodec.unescape(framed);
                    packetQueue.put(data);
                } else {
                    log.debug("bad checksum: expected {}, actual {}; dropping and nacking",
                            String.format("%02x", expected), String.format("%02x", actual));
                    if (!noAckMode) {
                        writeRaw(PacketCodec.NACK);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) {
                log.debug("packet reader ended: {}", e.getMessage());
            }
        } finally {
            Runnable listener = interruptListener;
            if (listener != null) {
                listener.run();
            }
            packetQueue.clear();
            packetQueue.offer(new byte[0]); // 哨兵：空数组表示 EOF
        }
    }

    private static int hexValue(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    private void offerAck(byte ack) {
        if (!noAckMode) {
            ackQueue.offer(ack);
        }
    }

    private synchronized void writeRaw(int b) throws IOException {
        out.write(b);
        out.flush();
    }

    /**
     * 发送一个停止/通知类异步回复（如断点命中、信号），同样受确认机制约束。
     */
    public void send(String payload) throws IOException {
        send(payload.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    public void send(byte[] payload) throws IOException {
        byte[] frame = PacketCodec.frame(payload);
        synchronized (this) {
            if (noAckMode) {
                out.write(frame);
                out.flush();
                return;
            }
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                ackQueue.clear();
                out.write(frame);
                out.flush();
                try {
                    Byte ack = ackQueue.poll(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (ack != null && ack == '+') {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for packet ack", e);
                }
                log.debug("no ack for packet (attempt {}), retransmitting", attempt + 1);
            }
            throw new IOException("peer did not acknowledge packet after " + MAX_RETRIES + " tries");
        }
    }

    /** 读取一个原始字节（用于忙碌拒绝消息等非数据包场景后直接关闭连接）。 */
    @Override
    public void close() {
        closed = true;
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
