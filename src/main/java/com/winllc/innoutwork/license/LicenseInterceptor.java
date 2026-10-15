package com.winllc.innoutwork.license;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses the requests of one licensed feature while the license does not cover it. Registered by
 * {@link LicenseConfig} on the feature's URL patterns; {@link LicenseExceptionHandler} turns the
 * refusal into a 403 page or JSON body.
 */
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;
    private final LicensedFeature feature;

    public LicenseInterceptor(LicenseService licenseService, LicensedFeature feature) {
        this.licenseService = licenseService;
        this.feature = feature;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!licenseService.isEnabled(feature.id())) {
            throw new LicenseRequiredException(feature);
        }
        return true;
    }
}
