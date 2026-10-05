package com.github.lechandonga.gdbremotetargetstubjava.tests;

import com.github.lechandonga.gdbremotetargetstubjava.tests.support.BusinessLoggingListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest
@ExtendWith(BusinessLoggingListener.class)
public class ApplicationTests {
    @Test
    @DisplayName("Spring 上下文启动，GDB stub 组件正常装配")
    public void contentLoad() {
    }
}
