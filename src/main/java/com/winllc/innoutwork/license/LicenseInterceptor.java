package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies the license to every request it is registered for (everything but sign-in/out, the license
 * screen, GET /api/license, the audit log and its pull API, static assets, actuator and the error page; see
 * {@link LicensingConfiguration}):
 * <ul>
 *   <li>UNLICENSED: refused. Browsers are sent to the license screen, API callers get 403 JSON.</li>
 *   <li>READ_ONLY: GET, HEAD and OPTIONS work; any other method is refused (error license_read_only).</li>
 *   <li>{@link RequiresFeature}: refused unless the license is in full effect and unlocks the feature.</li>
 * </ul>
 * {@link LicenseExceptionHandler} turns each refusal into the response. The check is left in a request
 * attribute, so the pages of the same request see the same one.
 */
public class LicenseInterceptor implements HandlerInterceptor {

    static final String REQUEST_ATTRIBUTE = LicenseInterceptor.class.getName() + ".check";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final LicenseManager licenses;
    /** Told about every check, so the audit trail notices a change of license mode; may be null. */
    private final Consumer<LicenseCheck> observer;

    public LicenseInterceptor(LicenseManager licenses) {
        this(licenses, null);
    }

    public LicenseInterceptor(LicenseManager licenses, Consumer<LicenseCheck> observer) {
        this.licenses = licenses;
        this.observer = observer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LicenseCheck check = licenses.current();
        request.setAttribute(REQUEST_ATTRIBUTE, check);
        if (observer != null) {
            observer.accept(check);
        }

        if (check.mode() == LicenseState.Mode.UNLICENSED) {
            throw new LicenseRefusedException(LicenseRefusedException.Reason.LICENSE_REQUIRED, check, null,
                    check.message());
        }
        if (check.mode() == LicenseState.Mode.READ_ONLY && !SAFE.contains(request.getMethod())) {
            throw new LicenseRefusedException(LicenseRefusedException.Reason.LICENSE_READ_ONLY, check, null,
                    "ReadyRoom is read-only: " + check.message());
        }
        if (handler instanceof HandlerMethod method) {
            RequiresFeature required = method.getMethodAnnotation(RequiresFeature.class);
            if (required == null) {
                required = method.getBeanType().getAnnotation(RequiresFeature.class);
            }
            if (required != null && !check.isFeatureEnabled(required.value().id())) {
                throw new LicenseRefusedException(LicenseRefusedException.Reason.FEATURE_NOT_LICENSED, check,
                        required.value(), required.value().label() + " is not included in this installation's license.");
            }
        }
        return true;
    }
}
