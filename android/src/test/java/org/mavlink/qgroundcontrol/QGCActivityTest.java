package org.mavlink.qgroundcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link QGCActivity} helpers that can be exercised without a
 * running Android runtime.
 */
public class QGCActivityTest {

    // -----------------------------------------------------------------------
    // isValidImportFileName  (covers the copyFileToDestination file-name gate)
    // -----------------------------------------------------------------------

    @Test
    public void isValidImportFileName_returnsTrueForLowerCasePlanExtension() {
        assertTrue(QGCActivity.isValidImportFileName("mission.plan"));
    }

    @Test
    public void isValidImportFileName_isCaseInsensitive() {
        assertTrue(QGCActivity.isValidImportFileName("MISSION.PLAN"));
        assertTrue(QGCActivity.isValidImportFileName("Mission.Plan"));
        assertTrue(QGCActivity.isValidImportFileName("test.PLAN"));
    }

    @Test
    public void isValidImportFileName_acceptsNamesWithSpacesAndSpecialChars() {
        assertTrue(QGCActivity.isValidImportFileName("my mission (v2).plan"));
        assertTrue(QGCActivity.isValidImportFileName("survey_grid.plan"));
    }

    @Test
    public void isValidImportFileName_returnsFalseForWrongExtension() {
        assertFalse(QGCActivity.isValidImportFileName("mission.kml"));
        assertFalse(QGCActivity.isValidImportFileName("mission.json"));
        assertFalse(QGCActivity.isValidImportFileName("mission.waypoints"));
        assertFalse(QGCActivity.isValidImportFileName("mission"));
    }

    @Test
    public void isValidImportFileName_returnsFalseForEmptyName() {
        assertFalse(QGCActivity.isValidImportFileName(""));
    }

    @Test
    public void isValidImportFileName_returnsFalseForNullName() {
        assertFalse(QGCActivity.isValidImportFileName(null));
    }

    @Test
    public void isValidImportFileName_returnsFalseForPlanAsPrefix() {
        // ".plan" must be the suffix, not just appear somewhere in the name.
        assertFalse(QGCActivity.isValidImportFileName("plan.kml"));
        assertFalse(QGCActivity.isValidImportFileName("mission.plan.bak"));
    }

    // -----------------------------------------------------------------------
    // jniOnImportResult — verifies the onImportResult JNI bridge declaration
    // -----------------------------------------------------------------------

    @Test
    public void onImportResult_isPublicNativeAndTakesStringReturnsVoid() throws NoSuchMethodException {
        final Method method = QGCActivity.class.getDeclaredMethod("onImportResult", String.class);

        assertTrue("onImportResult must be public",
                Modifier.isPublic(method.getModifiers()));
        assertTrue("onImportResult must be native (jniOnImportResult JNI bridge)",
                Modifier.isNative(method.getModifiers()));
        assertEquals("onImportResult must return void",
                void.class, method.getReturnType());
    }
}
