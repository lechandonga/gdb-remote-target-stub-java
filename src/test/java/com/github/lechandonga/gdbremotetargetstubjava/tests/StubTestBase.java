package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.session.GdbServer;
import com.github.lechandonga.gdbremotetargetstubjava.tests.support.FakeGdbClient;
import com.github.lechandonga.gdbremotetargetstubjava.tests.support.BusinessLoggingListener;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
@ExtendWith(BusinessLoggingListener.class)
public abstract class StubTestBase {

    @Autowired
    protected GdbServer server;

    @BeforeEach
    void resetBoard() {
        server.getBoard().reset();
    }

    protected FakeGdbClient connect() throws IOException {
        return new FakeGdbClient(server.getBoundPort());
    }

    /** 编码一条 32 位小端指令。 */
    protected static int instr(int opcode, int op1, int operand) {
        return (opcode & 0xff) | ((op1 & 0xff) << 8) | (operand << 16);
    }

    protected static byte[] program(int... words) {
        byte[] data = new byte[words.length * 4];
        for (int i = 0; i < words.length; i++) {
            data[i * 4] = (byte) words[i];
            data[i * 4 + 1] = (byte) (words[i] >>> 8);
            data[i * 4 + 2] = (byte) (words[i] >>> 16);
            data[i * 4 + 3] = (byte) (words[i] >>> 24);
        }
        return data;
    }
}
