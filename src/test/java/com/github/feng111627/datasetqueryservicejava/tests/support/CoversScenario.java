package com.github.feng111627.datasetqueryservicejava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares which business scenario a test class covers; shown in test logs. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CoversScenario {
    String value();
}
