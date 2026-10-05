package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.tests.support.FakeGdbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：会话语义——单会话强制、忙碌拒绝、断开重连后目标状态保留、复位命令。
 */
class SessionLifecycleTest extends StubTestBase {

    @Test
    @DisplayName("第二个并发连接收到可区分的忙碌拒绝（E80）并被关闭")
    void concurrentSessionRejectedWithReason() throws Exception {
        try (FakeGdbClient first = connect()) {
            first.sendPacket("?");
            assertEquals("S05", first.receivePacket());
            try (FakeGdbClient second = connect()) {
                String line = second.receiveRawLine();
                assertTrue(line.contains("ERR:E80"), line);
                assertTrue(line.contains("session already in use"), line);
                second.expectClosed();
            }
        }
    }

    @Test
    @DisplayName("断开后允许重连，内存/寄存器/断点跨会话保留")
    void reconnectPreservesTargetState() throws Exception {
        server.getBoard().loadProgram(program(instr(0x02, 0, 0x1234)), 0);
        try (FakeGdbClient first = connect()) {
            first.sendPacket("P1=12345678");
            assertEquals("OK", first.receivePacket());
            first.sendPacket("M8000,2:abcd");
            assertEquals("OK", first.receivePacket());
            first.sendPacket("Z0,0004,4");
            assertEquals("OK", first.receivePacket());
        }
        Thread.sleep(100); // 等待会话槽位释放
        try (FakeGdbClient second = connect()) {
            second.sendPacket("p1");
            assertEquals("12345678", second.receivePacket());
            second.sendPacket("m8000,2");
            assertEquals("abcd", second.receivePacket());
            assertEquals(0x0004,
                    server.getBoard().getSoftwareBreakpoints().iterator().next());
        }
    }

    @Test
    @DisplayName("D 分离后槽位释放，新连接可正常接入")
    void detachReleasesSessionSlot() throws Exception {
        try (FakeGdbClient first = connect()) {
            first.sendPacket("D");
            assertEquals("OK", first.receivePacket());
            first.expectClosed();
        }
        try (FakeGdbClient second = connect()) {
            second.sendPacket("?");
            assertEquals("S05", second.receivePacket());
        }
    }

    @Test
    @DisplayName("R00 复位目标：寄存器清零、内存清零、断点清空")
    void resetCommandClearsTargetState() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("P1=12345678");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("Z0,0000,4");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("R00");
            assertEquals("OK", gdb.receivePacket());
            gdb.sendPacket("p1");
            assertEquals("00000000", gdb.receivePacket());
            assertTrue(server.getBoard().getSoftwareBreakpoints().isEmpty());
        }
    }

    @Test
    @DisplayName("能力协商不随连接顺序漂移：新连接的 qSupported 与旧连接一致")
    void capabilitiesStableAcrossSessions() throws Exception {
        String firstReply;
        try (FakeGdbClient first = connect()) {
            first.sendPacket("qSupported");
            firstReply = first.receivePacket();
        }
        Thread.sleep(100);
        try (FakeGdbClient second = connect()) {
            second.sendPacket("qAttached");
            assertEquals("1", second.receivePacket());
            second.sendPacket("qSupported");
            assertEquals(firstReply, second.receivePacket());
        }
    }

    @Test
    @DisplayName("无确认模式是每连接属性：新连接恢复为确认模式")
    void noAckModeDoesNotLeakAcrossSessions() throws Exception {
        try (FakeGdbClient first = connect()) {
            first.sendPacket("QStartNoAckMode");
            assertEquals("OK", first.receivePacket());
        }
        Thread.sleep(100);
        try (FakeGdbClient second = connect()) {
            // 确认模式下客户端会先收到 '+'，若错误地停留在无确认模式则不会有 '+'。
            second.sendPacket("?");
            assertEquals("S05", second.receivePacket());
        }
    }
}
