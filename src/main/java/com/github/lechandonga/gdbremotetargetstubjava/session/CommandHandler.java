package com.github.lechandonga.gdbremotetargetstubjava.session;

import com.github.lechandonga.gdbremotetargetstubjava.protocol.PacketConnection;
import com.github.lechandonga.gdbremotetargetstubjava.target.BoardException;
import com.github.lechandonga.gdbremotetargetstubjava.target.SimBoard;

import java.nio.charset.StandardCharsets;
/**
 * 将一条命令包翻译为对 {@link SimBoard} 的操作并产生协议回复。
 *
 * <p>语法合法但当前不支持的命令统一返回空响应（空 payload），由 {@link GdbSession}
 * 负责编码。抛出 {@link BoardException} 的错误回复为 {@code Exx}。
 */
public class CommandHandler {

    private static final String TARGET_XML =
            "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE target SYSTEM \"gdb-target.dtd\">\n"
                    + "<target version=\"1.0\">"
                    + "<feature name=\"org.gnu.gdb.sim.core\">"
                    + "<reg name=\"g0\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g1\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g2\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g3\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g4\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g5\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g6\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"g7\" bitsize=\"32\" type=\"uint32\"/>"
                    + "<reg name=\"pc\" bitsize=\"32\" type=\"code_ptr\"/>"
                    + "</feature></target>";

    private final SimBoard board;
    private final PacketConnection connection;

    public CommandHandler(SimBoard board, PacketConnection connection) {
        this.board = board;
        this.connection = connection;
    }

    /** @return 回复 payload；{@code null} 表示不应答（连接需关闭等）。 */
    public byte[] handle(byte[] raw) {
        String cmd = new String(raw, StandardCharsets.ISO_8859_1);
        try {
            String reply = dispatch(cmd, raw);
            return reply == null ? null
                    : reply.getBytes(StandardCharsets.ISO_8859_1);
        } catch (BoardException e) {
            return e.errorReply().getBytes(StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException e) {
            // 参数语法错误：返回空响应，与“不支持的命令”保持一致的可重试语义。
            return new byte[0];
        }
    }

    private String dispatch(String cmd, byte[] raw) {
        if (cmd.isEmpty()) {
            return "";
        }
        char first = cmd.charAt(0);
        return switch (first) {
            case '?' -> {
                yield board.getStatus() == SimBoard.Status.EXITED
                        ? String.format("W%02x", board.getExitCode())
                        : String.format("S%02x", board.getLastSignal());
            }
            case 'g' -> readAllRegisters();
            case 'G' -> writeAllRegisters(cmd.substring(1));
            case 'p' -> readOneRegister(cmd.substring(1));
            case 'P' -> writeOneRegister(cmd.substring(1));
            case 'm' -> readMemory(cmd.substring(1));
            case 'M' -> writeMemoryHex(cmd.substring(1));
            case 'X' -> writeMemoryBinary(raw);
            case 'c' -> {
                if (cmd.length() > 1) {
                    String addr = cmd.substring(1);
                    if (!addr.isEmpty()) {
                        board.writeRegister(SimBoard.PC_INDEX, (int) parseHex(addr));
                    }
                }
                yield board.run();
            }
            case 's' -> {
                if (cmd.length() > 1) {
                    String addr = cmd.substring(1);
                    if (!addr.isEmpty()) {
                        board.writeRegister(SimBoard.PC_INDEX, (int) parseHex(addr));
                    }
                }
                yield board.step();
            }
            case 'Z', 'z' -> breakpoint(first == 'Z', cmd.substring(1));
            case 'D' -> {
                yield "OK";
            }
            case 'k' -> {
                board.reset();
                yield null;
            }
            case 'R' -> {
                board.reset();
                yield "OK";
            }
            case 'q' -> query(cmd);
            case 'Q' -> setVariable(cmd);
            case 'v' -> vCommand(cmd);
            case 'H', 'h' -> {
                yield "OK"; // 线程选择：单线程目标，接受任意选择
            }
            case 'T' -> {
                yield ""; // 线程是否存活：不支持，空响应
            }
            default -> {
                yield "";
            }
        };
    }

    private String readAllRegisters() {
        int[] regs = board.snapshotRegisters();
        StringBuilder sb = new StringBuilder(SimBoard.REG_COUNT * 8);
        for (int value : regs) {
            appendLe32Hex(sb, value);
        }
        return sb.toString();
    }

    private String writeAllRegisters(String body) {
        for (int i = 0; i < SimBoard.REG_COUNT; i++) {
            if (body.length() < (i + 1) * 8) {
                throw new BoardException(BoardException.E_BAD_ARGUMENT,
                        "G payload shorter than register file");
            }
            board.writeRegister(i, parseLeHex(body.substring(i * 8, i * 8 + 8)));
        }
        return "OK";
    }

    private String readOneRegister(String body) {
        int index = (int) parseHex(body);
        StringBuilder sb = new StringBuilder(8);
        appendLe32Hex(sb, board.readRegister(index));
        return sb.toString();
    }

    private String writeOneRegister(String body) {
        int eq = body.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("P command missing '='");
        }
        int index = (int) parseHex(body.substring(0, eq));
        int value = parseLeHex(body.substring(eq + 1));
        board.writeRegister(index, value);
        return "OK";
    }

    private String readMemory(String body) {
        int comma = body.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("m command missing ','");
        }
        int address = (int) parseHex(body.substring(0, comma));
        int length = (int) parseHex(body.substring(comma + 1));
        byte[] data = board.readMemory(address, length);
        StringBuilder sb = new StringBuilder(length * 2);
        for (byte datum : data) {
            sb.append(hex2(datum & 0xff));
        }
        return sb.toString();
    }

    private String writeMemoryHex(String body) {
        int comma = body.indexOf(',');
        int colon = body.indexOf(':');
        if (comma < 0 || colon < 0 || colon < comma) {
            throw new IllegalArgumentException("M command malformed");
        }
        int address = (int) parseHex(body.substring(0, comma));
        int length = (int) parseHex(body.substring(comma + 1, colon));
        byte[] data = decodeHexBody(body.substring(colon + 1), length);
        board.writeMemory(address, data);
        return "OK";
    }

    private String writeMemoryBinary(byte[] raw) {
        // X addr,length:<binary>：仅头部为 ASCII，直接定位第一个 ',' 与第一个 ':'。
        int comma = -1;
        int colon = -1;
        for (int i = 1; i < raw.length; i++) {
            int b = raw[i] & 0xff;
            if (b == ',' && comma < 0) {
                comma = i;
            } else if (b == ':') {
                colon = i;
                break;
            }
        }
        if (comma < 0 || colon < 0 || colon < comma) {
            throw new IllegalArgumentException("X command malformed");
        }
        int address = (int) parseHex(new String(raw, 1, comma - 1,
                StandardCharsets.ISO_8859_1));
        int length = (int) parseHex(new String(raw, comma + 1, colon - comma - 1,
                StandardCharsets.ISO_8859_1));
        int headerBytes = colon + 1;
        if (raw.length < headerBytes + length) {
            throw new BoardException(BoardException.E_BAD_ARGUMENT, "X payload truncated");
        }
        byte[] data = new byte[length];
        System.arraycopy(raw, headerBytes, data, 0, length);
        board.writeMemory(address, data);
        return "OK";
    }

    private String breakpoint(boolean insert, String body) {
        // type,addr,kind
        int c1 = body.indexOf(',');
        int c2 = c1 < 0 ? -1 : body.indexOf(',', c1 + 1);
        if (c1 < 0 || c2 < 0) {
            throw new IllegalArgumentException("Z/z command malformed");
        }
        int type = (int) parseHex(body.substring(0, c1));
        int address = (int) parseHex(body.substring(c1 + 1, c2));
        if (type == 0) {
            if (insert) {
                board.addSoftwareBreakpoint(address);
            } else {
                board.removeBreakpoint(0, address);
            }
            return "OK";
        }
        if (type == 1) {
            if (insert) {
                board.addHardwareBreakpoint(address);
            } else {
                board.removeBreakpoint(1, address);
            }
            return "OK";
        }
        // 观察点等其他类型暂不支持：空响应
        return "";
    }

    private String query(String cmd) {
        if (cmd.startsWith("qSupported")) {
            return "PacketSize=1000;QStartNoAckMode+;qXfer:features:read+";
        }
        if (cmd.equals("qAttached")) {
            return "1";
        }
        if (cmd.equals("qC")) {
            return "QC1";
        }
        if (cmd.equals("qTStatus")) {
            return "";
        }
        if (cmd.equals("qfThreadInfo")) {
            return "m1";
        }
        if (cmd.equals("qsThreadInfo")) {
            return "l";
        }
        if (cmd.equals("qOffsets")) {
            return "Text=0;Data=0;Bss=0";
        }
        if (cmd.startsWith("qXfer:features:read:target.xml:")) {
            String range = cmd.substring("qXfer:features:read:target.xml:".length());
            int colon = range.indexOf(',');
            int offset = (int) parseHex(range.substring(0, colon));
            int length = (int) parseHex(range.substring(colon + 1));
            if (offset >= TARGET_XML.length()) {
                return "l";
            }
            String part = TARGET_XML.substring(offset,
                    Math.min(offset + length, TARGET_XML.length()));
            boolean last = offset + length >= TARGET_XML.length();
            return (last ? "l" : "m") + part;
        }
        if (cmd.startsWith("qSymbol") || cmd.startsWith("qRcmd") || cmd.startsWith("qTfP")
                || cmd.startsWith("qTlP")) {
            return "";
        }
        return "";
    }

    private String setVariable(String cmd) {
        if (cmd.equals("QStartNoAckMode")) {
            connection.enableNoAckMode();
            return "OK";
        }
        // 其他 Q 包暂不支持：空响应
        return "";
    }

    private String vCommand(String cmd) {
        if (cmd.equals("vCont?")) {
            return "vCont;s;c";
        }
        if (cmd.equals("vMustReplyEmpty")) {
            return "";
        }
        if (cmd.equals("vKill")) {
            board.reset();
            return "OK";
        }
        return "";
    }

    private static void appendLe32Hex(StringBuilder sb, int value) {
        sb.append(hex2(value & 0xff))
                .append(hex2((value >>> 8) & 0xff))
                .append(hex2((value >>> 16) & 0xff))
                .append(hex2((value >>> 24) & 0xff));
    }

    private static String hex2(int v) {
        return String.format("%02x", v);
    }

    private byte[] decodeHexBody(String text, int length) {
        if (text.length() < length * 2) {
            throw new BoardException(BoardException.E_BAD_ARGUMENT, "M payload truncated");
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16);
            int lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("M payload not hexadecimal");
            }
            data[i] = (byte) ((hi << 4) | lo);
        }
        return data;
    }

    private static long parseHex(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("empty hexadecimal number");
        }
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) {
                throw new IllegalArgumentException("not hexadecimal: " + s);
            }
            value = (value << 4) | d;
        }
        return value;
    }

    /** 8 位十六进制（4 字节，目标小端）转为 int。 */
    private static int parseLeHex(String s) {
        return Integer.reverseBytes((int) parseHex(s));
    }
}
