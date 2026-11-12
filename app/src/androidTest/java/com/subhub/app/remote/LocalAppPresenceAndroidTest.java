package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.help.HelpActivity;
import org.junit.Test;

public final class LocalAppPresenceAndroidTest {
    @Test public void actualActivityResumePauseAndRecreationOwnPresence() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(LocalAppPresence source=new LocalAppPresence(context)) {
            source.install((android.app.Application)context.getApplicationContext());assertFalse(source.foreground());
            try(ActivityScenario<HelpActivity> page=ActivityScenario.launch(HelpActivity.class)) {
                assertTrue("Fixture needs a usable unlocked display",source.foreground());
                page.recreate();assertTrue(source.foreground());
                page.moveToState(Lifecycle.State.CREATED);assertFalse(source.foreground());
                page.moveToState(Lifecycle.State.RESUMED);assertTrue(source.foreground());
            }
            assertFalse(source.foreground());
        }
    }
}
