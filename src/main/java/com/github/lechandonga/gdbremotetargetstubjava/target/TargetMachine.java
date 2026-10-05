package com.github.lechandonga.gdbremotetargetstubjava.target;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Simulated embedded board: 10 x 32-bit registers (r0..r7, pc, sp),
 * 64 KiB of RAM mapped at 0x0000..0xFFFF, and a tiny 4-byte instruction set.
 *
 * Instruction encoding (little-endian, 4 bytes each):
 *   0x00              HALT            stop the program (exit)
 *   0x01              NOP
 *   0x02 rd rs 00     ADD  rd += rs
 *   0x03 rd lo hi     MOVI rd = imm16
 *   0x04 rd rs 00     LOAD rd = mem32[rs]
 *   0x05 rd rs 00     STORE mem32[rd] = rs
 *   0x06 00 00 00     BKPT            software breakpoint trap
 *   0x07 lo hi 00     JMP  pc = imm16
 *   0x08 rs lo hi     JNZ  if (rs != 0) pc = imm16 else pc += 4
 *
 * Breakpoints: software breakpoints (Z0, max 32) and hardware breakpoints
 * (Z1, max 4) are kept in separate sets and persist across debug sessions.
 */
public class TargetMachine {

    public static final int NUM_REGS = 10;
    public static final int REG_PC = 8;
    public static final int REG_SP = 9;
    public static final long MEM_BASE = 0;
    public static final long MEM_SIZE = 0x1_0000;
    public static final int MAX_SW_BREAKPOINTS = 32;
    public static final int MAX_HW_BREAKPOINTS = 4;
    private static final long INSTRUCTION_BUDGET = 10_000_000L;

    public enum State { STOPPED, RUNNING, EXITED }

    public enum StopReason { TRAP, EXIT, MEMORY_FAULT, INTERRUPTED, BUDGET_EXCEEDED }

    public record StopEvent(StopReason reason, long address) {}

    public static class MemoryFault extends Exception {
        public final long address;
        public MemoryFault(long address) {
            super("unmapped memory access at 0x" + Long.toHexString(address));
            this.address = address;
        }
    }

    public static class BreakpointLimitExceeded extends Exception {
        public BreakpointLimitExceeded(String kind) {
            super(kind + " breakpoint limit exceeded");
        }
    }

    private static class ExitSignal extends Exception {}

    private static class TrapSignal extends Exception {}

    private final int[] regs = new int[NUM_REGS];
    private final byte[] memory = new byte[(int) MEM_SIZE];
    private final Set<Long> swBreakpoints = new LinkedHashSet<>();
    private final Set<Long> hwBreakpoints = new LinkedHashSet<>();
    private volatile State state = State.STOPPED;
    private volatile boolean stopRequested = false;

    public synchronized State getState() {
        return state;
    }

    public synchronized int getRegister(int n) {
        return regs[n];
    }

    public synchronized void setRegister(int n, int value) {
        regs[n] = value;
    }

    public synchronized int[] snapshotRegisters() {
        return regs.clone();
    }

    public synchronized void loadRegisters(int[] values) {
        System.arraycopy(values, 0, regs, 0, Math.min(values.length, NUM_REGS));
    }

    public boolean isMapped(long addr, long len) {
        return addr >= MEM_BASE && len >= 0 && addr + len <= MEM_BASE + MEM_SIZE;
    }

    public synchronized byte[] readMemory(long addr, long len) throws MemoryFault {
        if (!isMapped(addr, len)) {
            throw new MemoryFault(addr);
        }
        byte[] out = new byte[(int) len];
        System.arraycopy(memory, (int) addr, out, 0, (int) len);
        return out;
    }

    public synchronized void writeMemory(long addr, byte[] data) throws MemoryFault {
        if (!isMapped(addr, data.length)) {
            throw new MemoryFault(addr);
        }
        System.arraycopy(data, 0, memory, (int) addr, data.length);
    }

    public synchronized void addBreakpoint(boolean hardware, long addr) throws BreakpointLimitExceeded {
        Set<Long> set = hardware ? hwBreakpoints : swBreakpoints;
        int limit = hardware ? MAX_HW_BREAKPOINTS : MAX_SW_BREAKPOINTS;
        if (!set.contains(addr) && set.size() >= limit) {
            throw new BreakpointLimitExceeded(hardware ? "hardware" : "software");
        }
        set.add(addr);
    }

    public synchronized void removeBreakpoint(boolean hardware, long addr) {
        (hardware ? hwBreakpoints : swBreakpoints).remove(addr);
    }

    public synchronized int breakpointCount(boolean hardware) {
        return (hardware ? hwBreakpoints : swBreakpoints).size();
    }

    public void requestStop() {
        stopRequested = true;
    }

    /** Reset to power-on state: registers cleared, state STOPPED. Memory and breakpoints are kept. */
    public synchronized void reset() {
        for (int i = 0; i < NUM_REGS; i++) {
            regs[i] = 0;
        }
        state = State.STOPPED;
        stopRequested = false;
    }

    /** Execute one instruction. Only legal from STOPPED state. */
    public StopEvent singleStep() {
        synchronized (this) {
            if (state != State.STOPPED) {
                throw new IllegalStateException("target is " + state);
            }
            state = State.RUNNING;
        }
        try {
            stepOnce();
            return stop(StopReason.TRAP, pc());
        } catch (ExitSignal e) {
            return exit();
        } catch (TrapSignal t) {
            return stop(StopReason.TRAP, pc());
        } catch (MemoryFault f) {
            return stop(StopReason.MEMORY_FAULT, f.address);
        } finally {
            synchronized (this) {
                if (state == State.RUNNING) {
                    state = State.STOPPED;
                }
            }
        }
    }

    /** Run until a breakpoint, HALT, memory fault, interrupt request or budget exhaustion. */
    public StopEvent continueRun() {
        synchronized (this) {
            if (state != State.STOPPED) {
                throw new IllegalStateException("target is " + state);
            }
            state = State.RUNNING;
            stopRequested = false;
        }
        try {
            // Step over a breakpoint sitting at the current pc before re-arming checks.
            stepOnce();
            long budget = INSTRUCTION_BUDGET;
            while (!stopRequested) {
                long pc = pc();
                if (breakpointAt(pc)) {
                    return stop(StopReason.TRAP, pc);
                }
                stepOnce();
                if (--budget <= 0) {
                    return stop(StopReason.BUDGET_EXCEEDED, pc());
                }
            }
            return stop(StopReason.INTERRUPTED, pc());
        } catch (ExitSignal e) {
            return exit();
        } catch (TrapSignal t) {
            return stop(StopReason.TRAP, pc());
        } catch (MemoryFault f) {
            return stop(StopReason.MEMORY_FAULT, f.address);
        } finally {
            synchronized (this) {
                if (state == State.RUNNING) {
                    state = State.STOPPED;
                }
            }
        }
    }

    private StopEvent stop(StopReason reason, long addr) {
        synchronized (this) {
            if (state == State.RUNNING) {
                state = State.STOPPED;
            }
        }
        return new StopEvent(reason, addr);
    }

    private StopEvent exit() {
        synchronized (this) {
            state = State.EXITED;
        }
        return new StopEvent(StopReason.EXIT, pc());
    }

    private long pc() {
        return regs[REG_PC] & 0xFFFF_FFFFL;
    }

    private synchronized boolean breakpointAt(long addr) {
        return swBreakpoints.contains(addr) || hwBreakpoints.contains(addr);
    }

    private void stepOnce() throws MemoryFault, ExitSignal, TrapSignal {
        int[] snap;
        synchronized (this) {
            snap = regs.clone();
        }
        long pc = snap[REG_PC] & 0xFFFF_FFFFL;
        if (!isMapped(pc, 4)) {
            throw new MemoryFault(pc);
        }
        byte[] insn;
        synchronized (this) {
            insn = new byte[4];
            System.arraycopy(memory, (int) pc, insn, 0, 4);
        }
        int op = insn[0] & 0xFF;
        int a = insn[1] & 0xFF;
        int b = insn[2] & 0xFF;
        int c = insn[3] & 0xFF;
        switch (op) {
            case 0x00 -> throw new ExitSignal();               // HALT
            case 0x01 -> setReg(REG_PC, (int) (pc + 4));       // NOP
            case 0x02 -> {                                     // ADD rd, rs
                checkRegPair(a, b, pc);
                setReg(a, snap[a] + snap[b]);
                setReg(REG_PC, (int) (pc + 4));
            }
            case 0x03 -> {                                     // MOVI rd, imm16
                checkReg(a, pc);
                setReg(a, b | (c << 8));
                setReg(REG_PC, (int) (pc + 4));
            }
            case 0x04 -> {                                     // LOAD rd, [rs]
                checkRegPair(a, b, pc);
                long addr = snap[b] & 0xFFFF_FFFFL;
                setReg(a, (int) read32(addr));
                setReg(REG_PC, (int) (pc + 4));
            }
            case 0x05 -> {                                     // STORE rs -> [rd]
                checkRegPair(a, b, pc);
                long addr = snap[a] & 0xFFFF_FFFFL;
                write32(addr, snap[b] & 0xFFFF_FFFFL);
                setReg(REG_PC, (int) (pc + 4));
            }
            case 0x06 -> {                                     // BKPT
                setReg(REG_PC, (int) (pc + 4));
                throw new TrapSignal();
            }
            case 0x07 -> setReg(REG_PC, a | (b << 8));         // JMP imm16
            case 0x08 -> {                                     // JNZ rs, imm16
                checkReg(a, pc);
                setReg(REG_PC, snap[a] != 0 ? (b | (c << 8)) : (int) (pc + 4));
            }
            default -> throw new MemoryFault(pc);              // illegal instruction
        }
    }

    private void checkReg(int r, long pc) throws MemoryFault {
        if (r >= NUM_REGS - 2) {
            throw new MemoryFault(pc);
        }
    }

    private void checkRegPair(int a, int b, long pc) throws MemoryFault {
        checkReg(a, pc);
        checkReg(b, pc);
    }

    private void setReg(int n, int value) {
        synchronized (this) {
            regs[n] = value;
        }
    }

    private long read32(long addr) throws MemoryFault {
        byte[] d = readMemory(addr, 4);
        return (d[0] & 0xFFL) | ((d[1] & 0xFFL) << 8) | ((d[2] & 0xFFL) << 16) | ((d[3] & 0xFFL) << 24);
    }

    private void write32(long addr, long value) throws MemoryFault {
        writeMemory(addr, new byte[]{
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)});
    }
}
