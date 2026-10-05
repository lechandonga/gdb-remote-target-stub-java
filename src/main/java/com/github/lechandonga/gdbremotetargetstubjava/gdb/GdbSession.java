package com.github.lechandonga.gdbremotetargetstubjava.gdb;

import com.github.lechandonga.gdbremotetargetstubjava.target.TargetMachine;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One GDB remote-serial-protocol session over a TCP socket.
 *
 * Packet layer: "$payload#cc" framing, 8-bit checksum, 0x7D escape (XOR 0x20),
 * '+'/'-' ack/nak with retransmission of the last reply, and QStartNoAckMode
 * negotiation. Corrupted or malformed input is NAKed (or discarded) and never
 * poisons the following packets. Unsupported but well-formed commands get an
 * empty reply ("$#00") as required by the protocol.
 *
 * Error replies used by this stub:
 *   E01  unmapped memory access
 *   E02  breakpoint limit exceeded
 *   E03  run command in illegal target state
 */
public class GdbSession {

    public static final String CAPABILITIES = "PacketSize=4000;QStartNoAckMode+;hwbreak+;swbreak+";

    private final Socket socket;
    private final TargetMachine target;
    private final InputStream in;
    private final OutputStream out;
    private final ExecutorService runner = Executors.newSingleThreadExecutor();

    private boolean noAckMode = false;
    private byte[] lastReply;
    private volatile boolean running = false;

    public GdbSession(Socket socket, TargetMachine target) throws IOException {
        this.socket = socket;
        this.target = target;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public void run() {
        log("session opened from " + socket.getRemoteSocketAddress());
        try {
            while (true) {
                String payload = readPacket();
                if (payload == null) {
                    continue; // checksum/framing error already NAKed
                }
                if (running) {
                    sendPacket("E03");
                    continue;
                }
                dispatch(payload);
            }
        } catch (EOFException e) {
            log("session closed by peer");
        } catch (IOException e) {
            log("session I/O error: " + e.getMessage());
        } finally {
            runner.shutdownNow();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------- packet layer ----------

    /** Reads one packet; returns the unescaped payload, or null if it was corrupted. */
    private String readPacket() throws IOException {
        int b;
        // Hunt for the frame start, handling out-of-band bytes on the way.
        while (true) {
            b = in.read();
            if (b == -1) {
                throw new EOFException();
            }
            if (b == '$') {
                break;
            }
            if (b == 0x03) {
                handleInterrupt();
            } else if (b == '-') {
                retransmit();
            }
            // '+' and stray garbage are ignored.
        }
        StringBuilder payload = new StringBuilder();
        int checksum = 0;
        while (true) {
            b = in.read();
            if (b == -1) {
                throw new EOFException();
            }
            if (b == '#') {
                break;
            }
            if (b == '$') {
                // A new frame start inside a packet: the previous bytes were a
                // truncated/corrupted packet. NAK it and restart framing here.
                log("framing error: '$' inside packet, discarding partial input");
                sendRaw('-');
                payload.setLength(0);
                checksum = 0;
                continue;
            }
            if (b == 0x7D) {
                int escaped = in.read();
                if (escaped == -1) {
                    throw new EOFException();
                }
                b = escaped ^ 0x20;
            }
            checksum = (checksum + b) & 0xFF;
            payload.append((char) b);
        }
        int hi = in.read();
        int lo = in.read();
        if (hi == -1 || lo == -1) {
            throw new EOFException();
        }
        int expected;
        try {
            expected = Integer.parseInt("" + (char) hi + (char) lo, 16);
        } catch (NumberFormatException e) {
            log("checksum error: non-hex checksum digits");
            sendRaw('-');
            return null;
        }
        if (expected != checksum) {
            log("checksum error: got " + expected + " want " + checksum + " for '" + payload + "'");
            sendRaw('-');
            return null;
        }
        if (!noAckMode) {
            sendRaw('+');
        }
        return payload.toString();
    }

    private void handleInterrupt() {
        if (running) {
            log("interrupt (Ctrl-C) requested");
            target.requestStop();
        }
    }

    private void retransmit() throws IOException {
        if (lastReply != null) {
            log("retransmitting last reply on NAK");
            synchronized (out) {
                out.write(lastReply);
                out.flush();
            }
        }
    }

    private void sendRaw(int b) throws IOException {
        synchronized (out) {
            out.write(b);
            out.flush();
        }
    }

    void sendPacket(String payload) throws IOException {
        StringBuilder frame = new StringBuilder("$");
        int checksum = 0;
        for (int i = 0; i < payload.length(); i++) {
            char ch = payload.charAt(i);
            if (ch == '$' || ch == '#' || ch == '}' || ch == '*') {
                frame.append('}').append((char) (ch ^ 0x20));
                checksum += 0x7D;
                checksum += ch ^ 0x20;
            } else {
                frame.append(ch);
                checksum += ch;
            }
        }
        frame.append('#').append(String.format("%02x", checksum & 0xFF));
        byte[] bytes = frame.toString().getBytes(StandardCharsets.US_ASCII);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
        lastReply = bytes;
    }

    // ---------- command dispatch ----------

    private void dispatch(String p) throws IOException {
        if (p.isEmpty()) {
            sendPacket("");
            return;
        }
        char cmd = p.charAt(0);
        switch (cmd) {
            case '?' -> sendPacket(stopReply(target.getState() == TargetMachine.State.EXITED
                    ? new TargetMachine.StopEvent(TargetMachine.StopReason.EXIT, 0)
                    : new TargetMachine.StopEvent(TargetMachine.StopReason.TRAP, 0)));
            case 'q' -> handleQuery(p);
            case 'Q' -> handleSet(p);
            case 'H' -> sendPacket("OK");
            case 'g' -> handleReadRegisters();
            case 'G' -> handleWriteRegisters(p.substring(1));
            case 'p' -> handleReadRegister(p.substring(1));
            case 'P' -> handleWriteRegister(p.substring(1));
            case 'm' -> handleReadMemory(p.substring(1));
            case 'M' -> handleWriteMemory(p.substring(1));
            case 'Z', 'z' -> handleBreakpoint(p);
            case 's' -> handleStep();
            case 'c' -> handleContinue();
            case 'k' -> {
                target.reset();
                sendPacket("OK");
            }
            default -> sendPacket(""); // legal but unsupported: empty reply
        }
    }

    private void handleQuery(String p) throws IOException {
        if (p.startsWith("qSupported")) {
            sendPacket(CAPABILITIES);
        } else if (p.equals("qAttached")) {
            sendPacket("1");
        } else if (p.equals("qfThreadInfo")) {
            sendPacket("m1");
        } else if (p.equals("qsThreadInfo")) {
            sendPacket("l");
        } else if (p.equals("qC")) {
            sendPacket("QC1");
        } else {
            sendPacket("");
        }
    }

    private void handleSet(String p) throws IOException {
        if (p.equals("QStartNoAckMode")) {
            noAckMode = true;
            sendPacket("OK");
            log("no-ack mode enabled");
        } else {
            sendPacket("");
        }
    }

    private void handleReadRegisters() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int r : target.snapshotRegisters()) {
            sb.append(toHexLE(r));
        }
        sendPacket(sb.toString());
    }

    private void handleWriteRegisters(String hex) throws IOException {
        try {
            int[] values = new int[TargetMachine.NUM_REGS];
            for (int i = 0; i < values.length; i++) {
                values[i] = fromHexLE(hex.substring(i * 8, i * 8 + 8));
            }
            target.loadRegisters(values);
            sendPacket("OK");
        } catch (RuntimeException e) {
            sendPacket("E04");
        }
    }

    private void handleReadRegister(String arg) throws IOException {
        try {
            int n = Integer.parseInt(arg, 16);
            if (n < 0 || n >= TargetMachine.NUM_REGS) {
                sendPacket("E04");
                return;
            }
            sendPacket(toHexLE(target.getRegister(n)));
        } catch (NumberFormatException e) {
            sendPacket("E04");
        }
    }

    private void handleWriteRegister(String arg) throws IOException {
        try {
            int eq = arg.indexOf('=');
            int n = Integer.parseInt(arg.substring(0, eq), 16);
            if (n < 0 || n >= TargetMachine.NUM_REGS) {
                sendPacket("E04");
                return;
            }
            target.setRegister(n, fromHexLE(arg.substring(eq + 1)));
            sendPacket("OK");
        } catch (RuntimeException e) {
            sendPacket("E04");
        }
    }

    private void handleReadMemory(String arg) throws IOException {
        try {
            String[] parts = arg.split(",");
            long addr = Long.parseLong(parts[0], 16);
            long len = Long.parseLong(parts[1], 16);
            byte[] data = target.readMemory(addr, len);
            StringBuilder sb = new StringBuilder();
            for (byte d : data) {
                sb.append(String.format("%02x", d));
            }
            sendPacket(sb.toString());
        } catch (TargetMachine.MemoryFault e) {
            sendPacket("E01");
        } catch (RuntimeException e) {
            sendPacket("E04");
        }
    }

    private void handleWriteMemory(String arg) throws IOException {
        try {
            int colon = arg.indexOf(':');
            String[] parts = arg.substring(0, colon).split(",");
            long addr = Long.parseLong(parts[0], 16);
            long len = Long.parseLong(parts[1], 16);
            String hex = arg.substring(colon + 1);
            if (hex.length() != len * 2) {
                sendPacket("E04");
                return;
            }
            byte[] data = new byte[(int) len];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            target.writeMemory(addr, data);
            sendPacket("OK");
        } catch (TargetMachine.MemoryFault e) {
            sendPacket("E01");
        } catch (RuntimeException e) {
            sendPacket("E04");
        }
    }

    private void handleBreakpoint(String p) throws IOException {
        try {
            boolean insert = p.charAt(0) == 'Z';
            int type = p.charAt(1) - '0';
            if (type != 0 && type != 1) {
                sendPacket(""); // unsupported breakpoint kind
                return;
            }
            String[] parts = p.substring(3).split(",");
            long addr = Long.parseLong(parts[0], 16);
            if (insert) {
                target.addBreakpoint(type == 1, addr);
            } else {
                target.removeBreakpoint(type == 1, addr);
            }
            sendPacket("OK");
        } catch (TargetMachine.BreakpointLimitExceeded e) {
            sendPacket("E02");
        } catch (RuntimeException e) {
            sendPacket("E04");
        }
    }

    private void handleStep() throws IOException {
        try {
            running = true;
            TargetMachine.StopEvent ev = target.singleStep();
            running = false;
            sendPacket(stopReply(ev));
        } catch (IllegalStateException e) {
            running = false;
            sendPacket("E03");
        }
    }

    private void handleContinue() {
        try {
            running = true;
            runner.submit(() -> {
                TargetMachine.StopEvent ev;
                try {
                    ev = target.continueRun();
                } catch (IllegalStateException e) {
                    ev = null;
                }
                running = false;
                try {
                    sendPacket(ev == null ? "E03" : stopReply(ev));
                } catch (IOException e) {
                    log("failed to send stop reply: " + e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            running = false;
            try {
                sendPacket("E03");
            } catch (IOException ignored) {
            }
        }
    }

    private String stopReply(TargetMachine.StopEvent ev) {
        return switch (ev.reason()) {
            case TRAP -> "S05";
            case EXIT -> "W00";
            case MEMORY_FAULT -> "S0B";
            case INTERRUPTED -> "S02";
            case BUDGET_EXCEEDED -> "S11";
        };
    }

    // ---------- helpers ----------

    static String toHexLE(int value) {
        return String.format("%02x%02x%02x%02x",
                value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, (value >> 24) & 0xFF);
    }

    static int fromHexLE(String hex) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v |= Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) << (8 * i);
        }
        return v;
    }

    static void log(String msg) {
        System.out.printf("[stub] %s%n", msg);
    }
}
