package com.github.feng111627.datasetqueryservicejava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs, for every test class, which business scenario it covers and the
 * pass/fail outcome of each test case, plus a per-class summary.
 */
public class BusinessCoverageLogger implements BeforeAllCallback, AfterAllCallback,
        BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger(BusinessCoverageLogger.class);
    private static final Map<String, AtomicInteger[]> COUNTERS = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        COUNTERS.put(context.getUniqueId(), new AtomicInteger[]{new AtomicInteger(), new AtomicInteger()});
        log.info("[COVERAGE] === {} | business scenario: {} ===",
                context.getRequiredTestClass().getSimpleName(), scenarioOf(context));
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("[COVERAGE] >> running case: {}", context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        boolean passed = context.getExecutionException().isEmpty();
        AtomicInteger[] counters = COUNTERS.get(classKey(context));
        if (counters != null) {
            counters[passed ? 0 : 1].incrementAndGet();
        }
        if (passed) {
            log.info("[COVERAGE] << PASS: {}", context.getDisplayName());
        } else {
            log.error("[COVERAGE] << FAIL: {} | cause: {}", context.getDisplayName(),
                    context.getExecutionException().map(Throwable::toString).orElse("?"));
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        AtomicInteger[] counters = COUNTERS.remove(context.getUniqueId());
        int passed = counters == null ? 0 : counters[0].get();
        int failed = counters == null ? 0 : counters[1].get();
        log.info("[COVERAGE] === {} finished: {} passed, {} failed ===",
                context.getRequiredTestClass().getSimpleName(), passed, failed);
    }

    private String classKey(ExtensionContext context) {
        // walk up to the class-level context to find the counter bucket
        ExtensionContext c = context;
        while (c.getParent().isPresent() && c.getParent().get().getTestClass().isPresent()) {
            c = c.getParent().get();
        }
        return c.getUniqueId();
    }

    private String scenarioOf(ExtensionContext context) {
        CoversScenario scenario = context.getRequiredTestClass()
                .getAnnotation(CoversScenario.class);
        return scenario == null ? "(not declared)" : scenario.value();
    }
}
