package com.github.lechandonga.gdbremotetargetstubjava;

import com.github.lechandonga.gdbremotetargetstubjava.gdb.GdbStubServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("stub.port", "5000"));
        GdbStubServer server = new GdbStubServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }));
        System.out.println("GDB remote target stub listening on port " + server.getPort()
                + " (connect with: target remote localhost:" + server.getPort() + ")");
        SpringApplication.run(Application.class, args);
    }
}
