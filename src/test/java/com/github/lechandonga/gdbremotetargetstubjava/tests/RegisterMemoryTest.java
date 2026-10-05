package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.tests.support.FakeGdbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：寄存器与内存读写（g/G/p/P/m/M/X）及未映射内存拒绝语义。
 */
class RegisterMemoryTest extends StubTestBase {

    @Test
    @DisplayName("p 读单寄存器与 P 写单寄存器，小端 32 位")
    void singleRegisterAccess() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("P1=12345678");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("p1");
            assertEquals("12345678", gdb.receivePacket());
            gdb.sendPacket("p8");
            assertEquals("00000000", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("g/G 整体读写 9 个寄存器")
    void registerFileAccess() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            String file = "000000010000000200000003000000040000000500000006000000070000000800000010";
            gdb.sendPacket("G" + file);
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("g");
            assertEquals(file, gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("非法寄存器编号返回 E01")
    void badRegisterRejected() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("p09");
            assertTrue(gdb.receivePacket().startsWith("E01"));
        }
    }

    @Test
    @DisplayName("M 写、m 读 RAM，数据一致")
    void hexMemoryAccess() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("M8000,4:deadbeef");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("m8000,4");
            assertEquals("deadbeef", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("X 二进制写（含需转义字节 0x03/#/$）读回一致")
    void binaryMemoryAccessWithEscapedBytes() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            byte[] framed = FakeGdbClient.frameWithBinaryHeader("X8000,4:",
                    new byte[]{0x03, '#', '$', '}'});
            gdb.sendRaw(framed);
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("m8000,4");
            assertEquals("0323247d", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("读未映射内存（越过 64KiB）返回 E10")
    void unmappedReadRejected() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("mfffe,4");
            String reply = gdb.receivePacket();
            assertTrue(reply.startsWith("E10"), reply);
        }
    }

    @Test
    @DisplayName("写未映射内存返回 E10，且后续交互仍然正常")
    void unmappedWriteRejectedDoesNotCorruptSession() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("Mfff0,11:" + "00".repeat(17));
            String reply = gdb.receivePacket();
            assertTrue(reply.startsWith("E10"), reply);
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
        }
    }
}
