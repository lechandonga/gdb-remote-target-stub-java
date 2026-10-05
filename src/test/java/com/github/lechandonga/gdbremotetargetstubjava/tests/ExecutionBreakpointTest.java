package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.tests.support.FakeGdbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：目标执行语义——单步、继续、软件/硬件断点命中、中断、程序结束与运行期故障。
 */
class ExecutionBreakpointTest extends StubTestBase {

    // 0x0000 MOVI g0, 0x0000
    // 0x0004 MOVI g1, 0x0001
    // 0x0008 ADD  g0, g1
    // 0x000c JZ   g0, 0x0014 （g0!=0 不跳）
    // 0x0010 JMP  0x0008
    // 0x0014 HALT 0x2a
    private byte[] loopProgram() {
        return program(
                instr(0x02, 0, 0x0000),
                instr(0x02, 1, 0x0001),
                instr(0x03, 0, 1),
                instr(0x07, 0, 0x0014),
                instr(0x06, 0, 0x0008),
                instr(0x01, 0, 0x002a));
    }

    @Test
    @DisplayName("单步逐条推进 pc，每条指令后 S05")
    void singleStepAdvancesPc() throws Exception {
        server.getBoard().loadProgram(loopProgram(), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("s");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("04000000", gdb.receivePacket());
            gdb.sendPacket("s");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("08000000", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("软件断点命中：c 在 0x000c 停下回 S05，? 可复查")
    void softwareBreakpointHits() throws Exception {
        server.getBoard().loadProgram(loopProgram(), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("Z0,000c,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("c");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("0c000000", gdb.receivePacket());
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("硬件断点 Z1 同样命中，与软件断点相互独立")
    void hardwareBreakpointHits() throws Exception {
        server.getBoard().loadProgram(loopProgram(), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("Z1,0010,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("c");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("10000000", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("断点超限：第 5 个软件断点返回 E13")
    void softwareBreakpointLimit() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            for (int addr : new int[]{0x0000, 0x0004, 0x0008, 0x000c}) {
                gdb.sendPacket(String.format("Z0,%04x,4", addr));
                assertEquals("OK", gdb.receivePacket());
            }
            gdb.sendPacket("Z0,0010,4");
            assertTrue(gdb.receivePacket().startsWith("E13"));
        }
    }

    @Test
    @DisplayName("硬件断点上限为 2（超出 E13）；RAM/非对齐地址 E14")
    void hardwareBreakpointLimitsAndAddressValidation() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("Z1,0000,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("Z1,0004,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("Z1,0008,4");
            assertTrue(gdb.receivePacket().startsWith("E13"));
            gdb.sendPacket("Z0,9000,4");
            assertTrue(gdb.receivePacket().startsWith("E14"));
            gdb.sendPacket("Z0,0002,4");
            assertTrue(gdb.receivePacket().startsWith("E14"));
        }
    }

    @Test
    @DisplayName("断点在单步时不触发，继续时才命中")
    void breakpointIgnoredBySingleStep() throws Exception {
        server.getBoard().loadProgram(loopProgram(), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("Z0,0004,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("s");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("04000000", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("程序跑到 HALT：c 返回 W2a，之后 ? 仍为 W2a")
    void programExitReported() throws Exception {
        server.getBoard().loadProgram(program(instr(0x01, 0, 0x002a)), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("c");
            assertEquals("W2a", gdb.receivePacket());
            gdb.sendPacket("?");
            assertEquals("W2a", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("退出后继续/单步属于非法状态运行，返回 E05")
    void runAfterExitRejected() throws Exception {
        server.getBoard().loadProgram(program(instr(0x01, 0, 0)), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("c");
            assertEquals("W00", gdb.receivePacket());
            gdb.sendPacket("c");
            assertTrue(gdb.receivePacket().startsWith("E05"));
            gdb.sendPacket("s");
            assertTrue(gdb.receivePacket().startsWith("E05"));
        }
    }

    @Test
    @DisplayName("运行中 0x03 中断：c 回复 S02，pc 停在循环体内")
    void ctrlCInterruptsRun() throws Exception {
        server.getBoard().loadProgram(loopProgram(), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("c");
            Thread.sleep(80);
            gdb.sendInterrupt();
            assertEquals("S02", gdb.receivePacket());
            gdb.sendPacket("p8");
            String pcHex = gdb.receivePacket();
            int pc = Integer.reverseBytes(Integer.parseUnsignedInt(pcHex.substring(0, 8), 16));
            assertTrue(pc >= 0x0008 && pc <= 0x0010, "pc=" + pcHex);
        }
    }

    @Test
    @DisplayName("非法指令触发 SIGILL（S04）")
    void illegalInstruction() throws Exception {
        server.getBoard().loadProgram(program(0xffffffff), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("c");
            assertEquals("S04", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("STORE 写 Flash 触发 SIGSEGV（S0b）")
    void storeToFlashFaults() throws Exception {
        server.getBoard().loadProgram(program(instr(0x05, 0, 0x0010)), 0);
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("c");
            assertEquals("S0b", gdb.receivePacket());
        }
    }
}
