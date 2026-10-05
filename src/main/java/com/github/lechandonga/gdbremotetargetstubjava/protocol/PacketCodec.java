package com.github.lechandonga.gdbremotetargetstubjava.protocol;

/**
 * GDB 远程串行协议封帧工具：{@code $ <payload> # cc}，支持二进制转义和 RLE。
 *
 * <p>转义：发送时 {@code # $ } 0x7d 0x03 0x7f *} 需转义为
 * {@code 0x7d, b ^ 0x20}；接收端做逆变换。校验和为 payload 原始字节
 * （转义前）之和模 256。
 */
public final class PacketCodec {

    public static final byte ACK = '+';
    public static final byte NACK = '-';
    public static final byte INTERRUPT = 0x03;

    private PacketCodec() {
    }

    public static int checksum(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xff;
        }
        return sum & 0xff;
    }

    /** 判断字节在发送时是否需要 0x7d 转义。 */
    public static boolean needsEscape(int b) {
        return b == '#' || b == '$' || b == '}' || b == '*' || b == 0x03 || b == 0x7d || b == 0x7f;
    }

    public static byte[] frame(String payload) {
        return frame(payload.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    public static byte[] frame(byte[] payload) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(payload.length + 4);
        out.write('$');
        int sum = 0;
        for (byte raw : payload) {
            int b = raw & 0xff;
            sum = (sum + b) & 0xff;
            if (needsEscape(b)) {
                out.write(0x7d);
                out.write(b ^ 0x20);
            } else {
                out.write(b);
            }
        }
        out.write('#');
        out.write(hexDigit((sum >>> 4) & 0xf));
        out.write(hexDigit(sum & 0xf));
        return out.toByteArray();
    }

    private static int hexDigit(int nibble) {
        return nibble < 10 ? '0' + nibble : 'a' + (nibble - 10);
    }

    /** 反转义一个数据包的 payload（已确认校验和正确、已去掉 $...#cc）。 */
    public static byte[] unescape(byte[] framedPayload) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(framedPayload.length);
        for (int i = 0; i < framedPayload.length; i++) {
            int b = framedPayload[i] & 0xff;
            if (b == 0x7d && i + 1 < framedPayload.length) {
                out.write((framedPayload[++i] & 0xff) ^ 0x20);
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }
}
