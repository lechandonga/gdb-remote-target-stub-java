package com.github.lechandonga.gdbremotetargetstubjava.tests.gdb;

import com.github.lechandonga.gdbremotetargetstubjava.gdb.GdbSession;
import com.github.lechandonga.gdbremotetargetstubjava.gdb.GdbStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests driving the stub with a loopback fake GDB client.
 * Each test logs the business scenario it covers and its result.
 */
class GdbStubTest {

    private static final long PROG = 0x1000;

    private GdbStubServer server;
    private FakeGdbClient gdb;

    @BeforeEach
    void setUp() throws Exception {
        server = new GdbStubServer(0);
        gdb = new FakeGdbClient(server.getPort());
    }

    @AfterEach
    void tearDown() throws Exception {
        gdb.close();
        server.close();
    }

    private static void scenario(String name) {
        System.out.printf("%n=== SCENARIO: %s ===%n", name);
    }

    private static void pass(String name) {
        System.out.printf("--- PASS: %s ---%n", name);
    }

    // MOVI r1,5 ; MOVI r2,7 ; ADD r1,r2 ; HALT
    private static final String PROGRAM =
            "03010500" + "03020700" + "02010200" + "00000000";

    private void loadProgram(String hex) throws Exception {
        assertEquals("OK", gdb.command("M" + Long.toHexString(PROG) + ","
                + Integer.toHexString(hex.length() / 2) + ":" + hex));
        assertEquals("OK", gdb.command("P8=" + le(0x1000)));
    }

    private static String le(int value) {
        return String.format("%02x%02x%02x%02x",
                value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, (value >> 24) & 0xFF);
    }

    private long pc() throws Exception {
        String hex = gdb.command("p8"); // 4 bytes, little-endian
        long v = 0;
        for (int i = 3; i >= 0; i--) {
            v = (v << 8) | Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return v;
    }

    @Test
    void checksumErrorTriggersNakAndCleanRetransmission() throws Exception {
        scenario("checksum error -> NAK -> retransmission accepted");
        gdb.sendPacketBadChecksum("qSupported");
        gdb.expectNak();
        gdb.sendPacket("qSupported");
        gdb.expectAck();
        String reply = gdb.readReply();
        assertEquals(GdbSession.CAPABILITIES, reply);
        // Session is still healthy afterwards.
        assertEquals("1", gdb.command("qAttached"));
        pass("checksum error / retransmission");
    }

    @Test
    void framingErrorInsidePacketDoesNotPoisonSession() throws Exception {
        scenario("truncated packet followed by new '$' -> NAK, next packet parsed cleanly");
        gdb.sendRaw("$garbage-without-terminator$?#3f");
        gdb.expectNak();
        gdb.expectAck();
        assertEquals("S05", gdb.readReply());
        pass("framing error recovery");
    }

    @Test
    void escapedBytesAreUnescapedBeforeChecksumAndDispatch() throws Exception {
        scenario("escaped byte 0x7D+0x1F decodes to '?' command");
        gdb.sendRaw("$}" + (char) 0x1F + "#3f"); // '?' = 0x3F, escaped as 0x7D 0x1F
        gdb.expectAck();
        assertEquals("S05", gdb.readReply());
        pass("escape handling");
    }

    @Test
    void unsupportedCommandReturnsEmptyResponse() throws Exception {
        scenario("legal but unsupported command -> empty reply $#00");
        assertEquals("", gdb.command("qXfer:features:read:target.xml:0,fff"));
        assertEquals("", gdb.command("vMustReplyEmpty"));
        assertEquals("", gdb.command("Z4,1000,1")); // unsupported breakpoint kind
        pass("unsupported command empty reply");
    }

    @Test
    void noAckModeIsNegotiatedAndDeterministic() throws Exception {
        scenario("QStartNoAckMode -> OK, subsequent packets get no ACK");
        assertEquals("OK", gdb.command("QStartNoAckMode"));
        gdb.setNoAckMode(true);
        assertEquals("1", gdb.command("qAttached"));
        // Capabilities must not drift between negotiations.
        assertEquals(GdbSession.CAPABILITIES, gdb.command("qSupported"));
        pass("no-ack mode negotiation");
    }

    @Test
    void registerReadWriteRoundTrip() throws Exception {
        scenario("G/g bulk and P/p single register read/write");
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            all.append(le(i * 0x111));
        }
        assertEquals("OK", gdb.command("G" + all));
        assertEquals(all.toString(), gdb.command("g"));
        assertEquals("OK", gdb.command("P3=" + le(0xdead)));
        assertEquals(le(0xdead), gdb.command("p3"));
        pass("register read/write");
    }

    @Test
    void memoryReadWriteRoundTrip() throws Exception {
        scenario("M/m memory write then read back");
        assertEquals("OK", gdb.command("M2000,4:01020304"));
        assertEquals("01020304", gdb.command("m2000,4"));
        pass("memory read/write");
    }

    @Test
    void unmappedMemoryAccessIsRejected() throws Exception {
        scenario("read/write outside 64KiB map -> E01, session stays alive");
        assertEquals("E01", gdb.command("mF000,2000"));
        assertEquals("E01", gdb.command("M1FFF0,2:0011"));
        assertEquals("1", gdb.command("qAttached"));
        pass("unmapped memory rejection");
    }

    @Test
    void softwareBreakpointHitThenRunToExit() throws Exception {
        scenario("Z0 software breakpoint: hit at address, then continue to HALT");
        loadProgram(PROGRAM);
        long bp = PROG + 8; // the ADD instruction
        assertEquals("OK", gdb.command("Z0," + Long.toHexString(bp) + ",4"));
        assertEquals("S05", gdb.command("c"));
        assertEquals(bp, pc());
        assertEquals("W00", gdb.command("c")); // runs off the breakpoint into HALT
        pass("software breakpoint hit + program exit");
    }

    @Test
    void hardwareBreakpointLimitIsEnforced() throws Exception {
        scenario("Z1 hardware breakpoints: 4 OK, 5th -> E02, remove then insert OK");
        for (int i = 0; i < 4; i++) {
            assertEquals("OK", gdb.command("Z1," + Integer.toHexString(0x100 + i * 4) + ",4"));
        }
        assertEquals("E02", gdb.command("Z1,200,4"));
        assertEquals("OK", gdb.command("z1,100,4"));
        assertEquals("OK", gdb.command("Z1,200,4"));
        pass("hardware breakpoint limit");
    }

    @Test
    void singleStepExecutesExactlyOneInstruction() throws Exception {
        scenario("s single-step: pc advances by one instruction, r1 updated");
        loadProgram(PROGRAM);
        assertEquals("S05", gdb.command("s"));
        assertEquals(PROG + 4, pc());
        assertEquals(le(5), gdb.command("p1"));
        assertEquals("S05", gdb.command("s"));
        assertEquals(PROG + 8, pc());
        pass("single step");
    }

    @Test
    void runInIllegalStateIsRejected() throws Exception {
        scenario("after HALT (W00), further c/s -> E03; k resets the target");
        loadProgram(PROGRAM);
        assertEquals("W00", gdb.command("c"));
        assertEquals("E03", gdb.command("c"));
        assertEquals("E03", gdb.command("s"));
        assertEquals("OK", gdb.command("k"));
        loadProgram(PROGRAM); // k reset the registers; reload pc before stepping
        assertEquals("S05", gdb.command("s")); // stopped again, stepping legal
        pass("illegal run state rejection");
    }

    @Test
    void ctrlCInterruptsRunningProgram() throws Exception {
        scenario("infinite loop + Ctrl-C (0x03) -> stop reply S02");
        loadProgram("07001000"); // JMP 0x1000 (loop forever)
        gdb.sendPacket("c");
        gdb.expectAck();
        Thread.sleep(200);
        gdb.interrupt();
        assertEquals("S02", gdb.readReply());
        pass("Ctrl-C interrupt");
    }

    @Test
    void reconnectKeepsTargetStateAndStableCapabilities() throws Exception {
        scenario("disconnect/reconnect: breakpoints and memory persist, capabilities stable");
        loadProgram(PROGRAM);
        assertEquals("OK", gdb.command("Z0," + Long.toHexString(PROG + 8) + ",4"));
        String caps = gdb.command("qSupported");
        gdb.close();
        Thread.sleep(300); // let the server notice the disconnect and free the session slot

        gdb = new FakeGdbClient(server.getPort());
        assertEquals(caps, gdb.command("qSupported"));
        assertEquals(PROGRAM.substring(0, 8),
                gdb.command("m" + Long.toHexString(PROG) + ",4"));
        assertEquals("OK", gdb.command("P8=" + le(0x1000)));
        assertEquals("S05", gdb.command("c")); // breakpoint from previous session still armed
        pass("reconnect with persistent target state");
    }

    @Test
    void concurrentSessionIsRejectedWithReason() throws Exception {
        scenario("second connection while session active -> explicit rejection");
        assertEquals("1", gdb.command("qAttached"));
        try (Socket second = new Socket("127.0.0.1", server.getPort())) {
            second.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(second.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            assertEquals(GdbStubServer.BUSY_MESSAGE.trim(), line.trim());
        }
        // First session is unaffected.
        assertEquals("1", gdb.command("qAttached"));
        pass("concurrent session rejection");
    }

    @Test
    void partialPacketThenDisconnectDoesNotAffectNextSession() throws Exception {
        scenario("incomplete packet + abrupt close -> next session clean");
        gdb.sendRaw("$qSupp"); // no '#', no checksum, then vanish
        gdb.close();
        Thread.sleep(200);
        gdb = new FakeGdbClient(server.getPort());
        assertEquals(GdbSession.CAPABILITIES, gdb.command("qSupported"));
        pass("incomplete input isolation");
    }
}
