package com.github.lechandonga.gdbremotetargetstubjava.target;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一块模拟的 16 位地址空间嵌入式小板子。
 *
 * <p>资源模型：
 * <ul>
 *     <li>内存：64KiB，{@code 0x0000-0x7FFF} 为代码/Flash 区，{@code 0x8000-0xFFFF} 为 RAM；
 *     调试写入（M/X 包）两个区都允许，模拟 STORE 指令只允许写 RAM。</li>
 *     <li>寄存器：g0..g7、pc 共 9 个 32 位寄存器，小端；{@link #REG_COUNT}。</li>
 *     <li>指令：32 位定长、小端、4 字节对齐（详见 README）。</li>
 * </ul>
 *
 * <p>断点不改动内存内容（软件断点以地址集合模拟，GDB 读到的内存始终是真实内容）。
 */
public class SimBoard {

    public static final int MEMORY_SIZE = 0x10000;
    public static final int FLASH_TOP = 0x7FFF;
    public static final int REG_COUNT = 9; // g0..g7, pc
    public static final int PC_INDEX = 8;
    public static final int MAX_SW_BREAKPOINTS = 4;
    public static final int MAX_HW_BREAKPOINTS = 2;

    public enum Status { STOPPED, RUNNING, EXITED }

    private final byte[] memory = new byte[MEMORY_SIZE];
    private final int[] registers = new int[REG_COUNT];
    private final Set<Integer> softwareBreakpoints = new TreeSet<>();
    private final Set<Integer> hardwareBreakpoints = new TreeSet<>();

    private Status status = Status.STOPPED;
    private int lastSignal = 5; // SIGTRAP：板子上电后等待调试器接管
    private int exitCode;
    private volatile boolean interruptRequested;

    public synchronized void reset() {
        Arrays.fill(memory, (byte) 0);
        Arrays.fill(registers, 0);
        softwareBreakpoints.clear();
        hardwareBreakpoints.clear();
        status = Status.STOPPED;
        lastSignal = 5;
        exitCode = 0;
        interruptRequested = false;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized int getLastSignal() {
        return lastSignal;
    }

    public synchronized int getExitCode() {
        return exitCode;
    }

    public synchronized int[] snapshotRegisters() {
        return registers.clone();
    }

    public synchronized Set<Integer> getSoftwareBreakpoints() {
        return new TreeSet<>(softwareBreakpoints);
    }

    public synchronized Set<Integer> getHardwareBreakpoints() {
        return new TreeSet<>(hardwareBreakpoints);
    }

    public void loadProgram(byte[] program, int offset) {
        if (offset < 0 || offset + program.length > MEMORY_SIZE) {
            throw new BoardException(BoardException.E_UNMAPPED_MEMORY,
                    "program image crosses 64KiB address space");
        }
        System.arraycopy(program, 0, memory, offset, program.length);
    }

    public int readRegister(int index) {
        if (index < 0 || index >= REG_COUNT) {
            throw new BoardException(BoardException.E_BAD_REGISTER, "register index " + index);
        }
        return registers[index];
    }

    public void writeRegister(int index, int value) {
        if (index < 0 || index >= REG_COUNT) {
            throw new BoardException(BoardException.E_BAD_REGISTER, "register index " + index);
        }
        registers[index] = value;
    }

    public byte[] readMemory(int address, int length) {
        checkRange(address, length);
        byte[] out = new byte[length];
        System.arraycopy(memory, address, out, 0, length);
        return out;
    }

    public void writeMemory(int address, byte[] data) {
        checkRange(address, data.length);
        System.arraycopy(data, 0, memory, address, data.length);
    }

    private void checkRange(int address, int length) {
        if (address < 0 || length <= 0 || address > MEMORY_SIZE - length) {
            throw new BoardException(BoardException.E_UNMAPPED_MEMORY,
                    String.format("unmapped memory 0x%04x length %d", address, length));
        }
    }

    public synchronized void addSoftwareBreakpoint(int address) {
        validateCodeAddress(address);
        if (address > FLASH_TOP) {
            throw new BoardException(BoardException.E_BP_BAD_ADDRESS,
                    "software breakpoint only allowed in code region 0x0000-0x7FFF");
        }
        if (!softwareBreakpoints.contains(address)
                && softwareBreakpoints.size() >= MAX_SW_BREAKPOINTS) {
            throw new BoardException(BoardException.E_BP_RESOURCE_FULL,
                    "software breakpoint slots exhausted (" + MAX_SW_BREAKPOINTS + ")");
        }
        softwareBreakpoints.add(address);
    }

    public synchronized void addHardwareBreakpoint(int address) {
        validateCodeAddress(address);
        if (!hardwareBreakpoints.contains(address)
                && hardwareBreakpoints.size() >= MAX_HW_BREAKPOINTS) {
 throw new BoardException(BoardException.E_BP_RESOURCE_FULL,
                    "hardware breakpoint slots exhausted (" + MAX_HW_BREAKPOINTS + ")");
        }
        hardwareBreakpoints.add(address);
    }

    private void validateCodeAddress(int address) {
        if (address < 0 || address > MEMORY_SIZE - 4 || (address & 3) != 0) {
            throw new BoardException(BoardException.E_BP_BAD_ADDRESS,
                    String.format("breakpoint address 0x%04x is not a 4-byte aligned code address",
                            address));
        }
    }

    public synchronized boolean removeBreakpoint(int kind, int address) {
        return kind == 1 ? hardwareBreakpoints.remove(address) : softwareBreakpoints.remove(address);
    }

    /** 调试器发起中断（Ctrl-C / 0x03）。 */
    public void requestInterrupt() {
        interruptRequested = true;
    }

    public boolean isInterruptRequested() {
        return interruptRequested;
    }

    /**
     * 单步执行一条指令；断点在单步语义下不触发（与 GDB 先摘断点再 step 的做法一致）。
     *
     * @return 停止原因（信号 Sxx / 退出 Wxx），正常返回 {@code null}
     */
    public synchronized String step() {
        if (status == Status.RUNNING) {
            throw new BoardException(BoardException.E_NOT_RUNNABLE, "target already running");
        }
        if (status == Status.EXITED) {
            throw new BoardException(BoardException.E_NOT_RUNNABLE,
                    "target has exited; reset or reconnect before running");
        }
        String fault = executeOneInstruction();
        if (fault != null) {
            return fault;
        }
        return "S05";
    }

    /** 继续运行，直到断点/信号/退出/中断。调用线程即运行线程。 */
    public String run() {
        synchronized (this) {
            if (status == Status.RUNNING) {
                throw new BoardException(BoardException.E_NOT_RUNNABLE, "target already running");
            }
            if (status == Status.EXITED) {
                throw new BoardException(BoardException.E_NOT_RUNNABLE,
                        "target has exited; reset or reconnect before running");
            }
            status = Status.RUNNING;
            interruptRequested = false;
        }
        try {
            long budgetGuard = 0;
            while (true) {
                int pc;
                synchronized (this) {
                    pc = registers[PC_INDEX];
                    boolean hit = softwareBreakpoints.contains(pc)
                            || hardwareBreakpoints.contains(pc);
                    if (hit) {
                        return stop(5, Status.STOPPED);
                    }
                }
                String fault;
                synchronized (this) {
                    fault = executeOneInstruction();
                }
                if (fault != null) {
                    return fault;
                }
                if (interruptRequested) {
                    synchronized (this) {
                        return stop(2, Status.STOPPED); // SIGINT
                    }
                }
                // 让出 CPU 时间，保证中断/停止响应及时且不忙等。
                if ((++budgetGuard & 0xFF) == 0) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        synchronized (this) {
                            return stop(2, Status.STOPPED);
                        }
                    }
                }
            }
        } finally {
            synchronized (this) {
                if (status == Status.RUNNING) {
                    status = Status.STOPPED;
                }
            }
        }
    }

    private String stop(int signal, Status newStatus) {
        lastSignal = signal;
        status = newStatus;
        return String.format("S%02x", signal);
    }

    /**
     * 执行一条指令。返回停止回复；正常返回 {@code null}。
     * 调用方必须持有对象锁（run 循环中已加锁）。
     */
    private String executeOneInstruction() {
        int pc = registers[PC_INDEX];
        if (pc < 0 || pc > MEMORY_SIZE - 4 || (pc & 3) != 0) {
            lastSignal = 11; // SIGSEGV
            status = Status.STOPPED;
            return "S0b";
        }
        int word = memory[pc] & 0xff
                | ((memory[pc + 1] & 0xff) << 8)
                | ((memory[pc + 2] & 0xff) << 16)
                | ((memory[pc + 3] & 0xff) << 24);
        int opcode = word & 0xff;
        int op1 = (word >>> 8) & 0xff;
        int operand = word >>> 16;
        int nextPc = pc + 4;

        switch (opcode) {
            case 0x00 -> { // NOP
                registers[PC_INDEX] = nextPc;
            }
            case 0x01 -> { // HALT imm8 -> 进程以 imm8 为退出码结束
                exitCode = operand & 0xff;
                status = Status.EXITED;
                return String.format("W%02x", exitCode);
            }
            case 0x02 -> { // MOVI g[op1], imm16(零扩展)
                registers[op1 & 7] = operand & 0xffff;
                registers[PC_INDEX] = nextPc;
            }
            case 0x03 -> { // ADD g[op1], g[operand&7]
                registers[op1 & 7] += registers[operand & 7];
                registers[PC_INDEX] = nextPc;
            }
            case 0x04 -> { // LOAD g[op1] = (unsigned16) mem16[imm16]
                int addr = operand & 0xffff;
                registers[op1 & 7] = load16(addr);
                registers[PC_INDEX] = nextPc;
            }
            case 0x05 -> { // STORE mem16[imm16] = g[op1] 的低 16 位（仅 RAM）
                int addr = operand & 0xffff;
                if (addr <= FLASH_TOP) {
                    lastSignal = 11; // SIGSEGV：写 Flash
                    status = Status.STOPPED;
                    return "S0b";
                }
                store16(addr, registers[op1 & 7]);
                registers[PC_INDEX] = nextPc;
            }
            case 0x06 -> { // JMP imm16
                registers[PC_INDEX] = operand & 0xffff;
            }
            case 0x07 -> { // JZ g[op1], imm16
                registers[PC_INDEX] = registers[op1 & 7] == 0 ? operand & 0xffff : nextPc;
            }
            default -> {
                lastSignal = 4; // SIGILL
                status = Status.STOPPED;
                return "S04";
            }
        }
        return null;
    }

    private int load16(int address) {
        checkRange(address, 2);
        return (memory[address] & 0xff) | ((memory[address + 1] & 0xff) << 8);
    }

    private void store16(int address, int value) {
        checkRange(address, 2);
        memory[address] = (byte) value;
        memory[address + 1] = (byte) (value >>> 8);
    }
}
