package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.tests.support.FakeGdbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 业务：远程协议封帧 / 校验 / 转义 / 确认重传 / 无确认模式协商。
 */
class ProtocolFramingTest extends StubTestBase {

    @Test
    @DisplayName("正常封帧往返：? 得到初始停止原因 S05")
    void roundTrip() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("坏校验和：stub 回 '-' 并要求重传，重传后正常且不污染后续包")
    void badChecksumTriggersNackAndRetransmit() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacketWithBadChecksum("?");
            assertEquals('-', (char) gdb.readRawByte());
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("起始标记前的噪声字节被静默丢弃，随后正常包可处理")
    void leadingNoiseIgnored() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendRaw(new byte[]{'x', 'y', 0x01, '\n'});
            gdb.sendPacket("?");
            assertEquals("S05", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("不完整输入后补全：半包不会被误处理，补齐后得到响应")
    void incompletePacketCompletedLater() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            byte[] frame = FakeGdbClient.frameOf("?");
            gdb.sendRaw(new byte[]{frame[0], frame[1]}); // "$?"
            Thread.sleep(150);
            byte[] rest = new byte[frame.length - 2];
            System.arraycopy(frame, 2, rest, 0, rest.length);
            gdb.sendRaw(rest);
            assertEquals("S05", gdb.receivePacket());
        }
    }

    @Test
    @DisplayName("无确认模式协商：QStartNoAckMode 后双方均不再发 +/-")
    void noAckModeNegotiation() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("QStartNoAckMode");
            assertEquals("OK", gdb.receivePacket());
            gdb.setNoAck(true);
            gdb.sendPacket("?");
            String reply = new String(gdb.receivePacketBytesNoAck(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertEquals("S05", reply);
        }
    }

    @Test
    @DisplayName("能力协商稳定：多次 qSupported 回复完全一致，不受重试/顺序影响")
    void capabilitiesAreStable() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            String first;
            gdb.sendPacket("qSupported");
            first = gdb.receivePacket();
            gdb.sendPacketWithBadChecksum("qSupported");
            assertEquals('-', (char) gdb.readRawByte());
            gdb.sendPacket("qSupported");
            String second = gdb.receivePacket();
            assertEquals(first, second);
            assertNotEquals("", first);
        }
    }

    @Test
    @DisplayName("语法合法但不支持的命令返回空响应而非错误")
    void unsupportedCommandReturnsEmpty() throws Exception {
        try (FakeGdbClient gdb = connect()) {
            gdb.sendPacket("vMustReplyEmpty");
            assertEquals("", gdb.receivePacket());
            gdb.sendPacket("qTStatus");
            assertEquals("", gdb.receivePacket());
            gdb.sendPacket("Z2,8000,4");
            assertEquals("", gdb.receivePacket());
        }
    }
}
