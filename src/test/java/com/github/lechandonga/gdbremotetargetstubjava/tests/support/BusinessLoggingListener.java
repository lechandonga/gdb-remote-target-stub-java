package com.github.lechandonga.gdbremotetargetstubjava.tests.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 按业务域记录单测执行日志：每个业务类开始、每条用例结果、每个业务汇总。
 */
public class BusinessLoggingListener implements TestWatcher {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-COVERAGE");

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("[业务:{}] 用例 PASS :: {}", business(context), displayName(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.warn("[业务:{}] 用例 FAIL :: {} -- {}",
                business(context), displayName(context), cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("[业务:{}] 用例 ABORTED :: {}", business(context), displayName(context));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional reason) {
        log.info("[业务:{}] 用例 DISABLED :: {} ({})",
                business(context), displayName(context), reason.orElse(""));
    }

    private static String business(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }

    private static String displayName(ExtensionContext context) {
        return context.getDisplayName();
    }
}
