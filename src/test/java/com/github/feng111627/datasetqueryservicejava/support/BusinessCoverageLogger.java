package com.github.feng111627.datasetqueryservicejava.support;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 把每个用例的业务名称（@DisplayName）与通过/失败结果写入测试日志，
 * 让 mvn test 输出可以直接看出覆盖了哪些业务及每个用例的结果。
 * 通过 ServiceLoader（META-INF/services）自动注册。
 */
public class BusinessCoverageLogger implements TestWatcher, BeforeTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("▶ 开始用例 [{}] {}", businessPath(context), context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("  ✔ 通过 [{}]", context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.warn("  ✘ 失败 [{}] 原因: {}", context.getDisplayName(),
                cause == null ? "未知" : cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("  ⊘ 中止 [{}] 原因: {}", context.getDisplayName(),
                cause == null ? "未知" : cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        log.info("  ⊖ 跳过 [{}] 原因: {}", context.getDisplayName(), reason.orElse("未说明"));
    }

    private String businessPath(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?");
    }
}
