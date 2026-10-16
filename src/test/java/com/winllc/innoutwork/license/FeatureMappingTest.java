package com.winllc.innoutwork.license;

import com.winllc.innoutwork.controller.GroupController;
import com.winllc.innoutwork.controller.HomeController;
import com.winllc.innoutwork.controller.MetricsController;
import com.winllc.innoutwork.controller.OrgChartController;
import com.winllc.innoutwork.controller.ProfileController;
import com.winllc.innoutwork.controller.SettingsController;
import com.winllc.innoutwork.controller.UserController;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import com.winllc.innoutwork.rest.OrgNodeRestService;
import com.winllc.innoutwork.rest.SettingsRestService;
import com.winllc.innoutwork.rest.UserEventRestService;
import com.winllc.innoutwork.rest.UserRestService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The real controllers carry the feature the catalog maps them to (README, Licensing). The gating
 * tests run against stand-ins on the same URLs, so this is what ties them to the real code.
 */
class FeatureMappingTest {

    private static LicensedFeature onClass(Class<?> type) {
        RequiresFeature r = type.getAnnotation(RequiresFeature.class);
        return r == null ? null : r.value();
    }

    private static LicensedFeature onMethod(Class<?> type, String name) {
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(type.getSimpleName() + "." + name + " not found"));
        RequiresFeature r = method.getAnnotation(RequiresFeature.class);
        return r == null ? null : r.value();
    }

    @Test
    void attendanceIsTheCheckInApi() {
        assertEquals(LicensedFeature.ATTENDANCE, onClass(CheckInOutRestService.class));
        assertEquals(LicensedFeature.ATTENDANCE, onMethod(UserRestService.class, "getUserCheckInOutRecords"));
    }

    @Test
    void calendarIsTheEventAndOrganizationCalendarApis() {
        assertEquals(LicensedFeature.CALENDAR, onClass(UserEventRestService.class));
        assertEquals(LicensedFeature.CALENDAR, onClass(SettingsRestService.class));
    }

    @Test
    void metricsIsMetricsAndEveryReport() {
        assertEquals(LicensedFeature.METRICS, onClass(MetricsController.class));
        assertEquals(LicensedFeature.METRICS, onMethod(UserController.class, "myReports"));
        assertEquals(LicensedFeature.METRICS, onMethod(GroupController.class, "generateReport"));
        assertEquals(LicensedFeature.METRICS, onMethod(UserRestService.class, "getMyReports"));
    }

    @Test
    void orgChartIsTheChartItsApiAndItsParseRules() {
        assertEquals(LicensedFeature.ORG_CHART, onClass(OrgChartController.class));
        assertEquals(LicensedFeature.ORG_CHART, onClass(OrgNodeRestService.class));
        Set<LicensedFeature> rules = Arrays.stream(SettingsController.class.getDeclaredMethods())
                .filter(m -> m.getName().endsWith("OrgParsingRule"))
                .map(m -> m.getAnnotation(RequiresFeature.class))
                .map(r -> r == null ? null : r.value())
                .collect(Collectors.toSet());
        assertEquals(Set.of(LicensedFeature.ORG_CHART), rules);
    }

    @Test
    void theCorePagesAreNotGatedAsAWhole() {
        // Home and profile stay; their attendance and calendar parts are hidden in the templates.
        assertNull(onClass(HomeController.class));
        assertNull(onClass(ProfileController.class));
        assertNull(onClass(SettingsController.class));
    }
}
