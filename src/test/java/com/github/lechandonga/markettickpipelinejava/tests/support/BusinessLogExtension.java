package com.github.lechandonga.markettickpipelinejava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让单测日志能清晰展示"覆盖了哪些业务、各业务下哪些用例及结果"：
 * 每个用例开始前打印业务分组与用例名，类结束后打印业务分组完成横幅。
 */
public class BusinessLogExtension implements BeforeEachCallback, AfterAllCallback {

    private static final Logger LOG = LoggerFactory.getLogger("BUSINESS-COVERAGE");

    @Override
    public void beforeEach(ExtensionContext context) {
        String business = context.getRequiredTestClass().getSimpleName();
        String caseName = context.getRequiredTestMethod().getName();
        String display = context.getDisplayName();
        LOG.info("");
        LOG.info("├─ [业务] {} ─ [用例] {} {}", business, caseName,
                display.equals(caseName + "()") ? "" : "(" + display + ")");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        LOG.info("");
        LOG.info("└─ [业务分组完成] {}", context.getRequiredTestClass().getSimpleName());
    }
}
