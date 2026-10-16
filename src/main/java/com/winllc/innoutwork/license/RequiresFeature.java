package com.winllc.innoutwork.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or handler method as part of a licensed feature. {@link LicenseInterceptor}
 * refuses requests to it with 403 unless the installed license is in full effect and unlocks the
 * feature. A method's annotation wins over its class's.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    LicensedFeature value();
}
