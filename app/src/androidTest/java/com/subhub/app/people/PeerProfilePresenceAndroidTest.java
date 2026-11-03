package com.subhub.app.people;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

/** Actual native profile renderers with synthetic observations; no controller or transport fixture. */
public final class PeerProfilePresenceAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automationState=new com.subhub.app.UiAutomationStateRule();
    @Test @SdkSuppress(minSdkVersion=31)
    public void bothCounterpartProfilesShowScopedContactAndUnknownWithoutTextClipping() throws Exception {
        android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File evidence=new File(target.getCacheDir(),"peer-presence-"+UUID.randomUUID());assertTrue(evidence.mkdir());
        PersonId sub=remote('b'),dom=remote('a');long old=System.currentTimeMillis()-10*60_000L;
        try(ActivityScenario<HelpActivity> scenario=ActivityScenario.launch(HelpActivity.class)) {
            for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.GERMAN}) {
                AtomicReference<LinearLayout> rendered=new AtomicReference<>();CountDownLatch frame=new CountDownLatch(1);
                scenario.onActivity(activity->{
                    Configuration configuration=new Configuration(target.getResources().getConfiguration());configuration.setLocale(locale);
                    Context context=new androidx.appcompat.view.ContextThemeWrapper(target.createConfigurationContext(configuration),R.style.Theme_SubHub);
                    LinearLayout column=PersonUi.column(context);column.setPadding(PersonUi.dp(context,16),0,PersonUi.dp(context,16),0);
                    ScrollView scroll=new ScrollView(activity);scroll.addView(column);activity.setContentView(scroll);rendered.set(column);
                    PersonSnapshot saved=snapshot(sub,PersonSnapshot.Connection.OFFLINE,old,PersonSnapshot.Freshness.STALE);
                    ProfileViews.identity(column,saved);
                    LinearLayout counterpart=PersonUi.card(column);counterpart.setTag("incoming_dom_profile");
                    PersonSnapshot local=snapshot(PersonId.LOCAL,PersonSnapshot.Connection.LOCAL,0,PersonSnapshot.Freshness.CURRENT)
                            .withContractDom(ProfileData.empty("Saved Dom"),dom,new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.OFFLINE,old));
                    ContractHeader.add(counterpart,local,false,ignored->{});
                    ProfileViews.lastOnline(column,new PersonId(dom.value()+"_unknown"),0);
                    ProfileViews.lastOnline(column,new PersonId(dom.value()+"_future"),System.currentTimeMillis()+3_600_000);
                    ProfileViews.lastOnline(column,PersonId.LOCAL,old);
                    LinearLayout pending=PersonUi.card(column);pending.setTag("pending_dom_profile");
                    ContractHeader.add(pending,local.withContractDom(local.contractDom,dom,
                            new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.PENDING,0)),false,ignored->{});
                    LinearLayout revoked=PersonUi.card(column);revoked.setTag("revoked_dom_profile");
                    ContractHeader.add(revoked,local.withContractDom(local.contractDom,dom,
                            new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.REVOKED,old)),false,ignored->{});
                    column.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);column.requestLayout();column.invalidate();
                });
                assertTrue("Native profile frame did not commit",frame.await(8,TimeUnit.SECONDS));
                // Retain the global window capture separately; its current-content match is unverified.
                Bitmap pixels=automation.takeScreenshot();assertNotNull(pixels);
                try(FileOutputStream output=new FileOutputStream(new File(evidence,locale.getLanguage()+".png"))){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,output));}
                finally{pixels.recycle();}
                scenario.onActivity(activity->{
                    LinearLayout column=rendered.get();Context context=column.getContext();
                    retainNativeComponentRender(activity,column,locale,sub,dom,evidence);
                    retainTextBounds(column,new File(evidence,locale.getLanguage()+".json"));
                    TextView outgoing=column.findViewWithTag("profile_last_online:"+sub.value());
                    TextView incoming=((View)column.findViewWithTag("incoming_dom_profile")).findViewWithTag("profile_last_online:"+dom.value());
                    assertNotNull(outgoing);assertNotNull(incoming);assertEquals(outgoing.getText().toString(),incoming.getText().toString());
                    assertFalse(outgoing.getText().toString().contains(context.getString(R.string.remote_unknown)));
                    assertNotNull(column.findViewWithTag("profile_cached_data:"+sub.value()));
                    String unknown=context.getString(R.string.peer_last_online,context.getString(R.string.remote_unknown));
                    assertEquals(unknown,((TextView)column.findViewWithTag("profile_last_online:"+dom.value()+"_unknown")).getText().toString());
                    assertEquals(unknown,((TextView)column.findViewWithTag("profile_last_online:"+dom.value()+"_future")).getText().toString());
                    assertNull(column.findViewWithTag("profile_last_online:"+PersonId.LOCAL.value()));
                    View pending=column.findViewWithTag("pending_dom_profile"),revoked=column.findViewWithTag("revoked_dom_profile");
                    assertTrue(text(pending).contains(context.getString(R.string.role_connection_pending)));
                    assertTrue(text(revoked).contains(context.getString(R.string.role_connection_revoked)));
                    assertFalse(text(pending).contains(context.getString(R.string.role_connection_connected)));
                    assertFalse(text(revoked).contains(context.getString(R.string.role_connection_connected)));
                    assertTextFits(column);
                });
            }
        }
    }
    /** Draws the current native View tree, never the physical display or global window compositor. */
    private static void retainNativeComponentRender(HelpActivity activity,LinearLayout column,Locale requested,
            PersonId sub,PersonId dom,File evidence) {
        assertSame(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());
        Context context=column.getContext();Locale actual=context.getResources().getConfiguration().getLocales().get(0);
        android.view.ViewGroup content=activity.findViewById(android.R.id.content);
        boolean current=column.getParent() instanceof ScrollView && content.getChildCount()==1
                && content.getChildAt(0)==column.getParent() && ((ScrollView)column.getParent()).getChildCount()==1
                && ((ScrollView)column.getParent()).getChildAt(0)==column
                && column.getRootView()==activity.getWindow().getDecorView();
        String language=requested.getLanguage(),copy=text(column);
        JSONArray rows=new JSONArray();
        try {
            collectTextBounds(column,rows);
            JSONObject layout=new JSONObject().put("evidenceKind","native-view-render")
                    .put("physicalDisplayProof",false).put("globalWindowContentProof",false)
                    .put("windowScreenshot",language+".png").put("windowScreenshotContentVerified",false)
                    .put("renderFile",language+"-render.png").put("renderSurface","R.color.background")
                    .put("requestedLocale",requested.toLanguageTag()).put("actualLocale",actual.toLanguageTag())
                    .put("host",activity.getClass().getName()).put("hostIdentity",System.identityHashCode(activity))
                    .put("columnIdentity",System.identityHashCode(column)).put("currentContent",current)
                    .put("attached",column.isAttachedToWindow()).put("shown",column.isShown())
                    .put("laidOut",column.isLaidOut()).put("layoutRequested",column.isLayoutRequested())
                    .put("width",column.getWidth()).put("height",column.getHeight()).put("actualText",copy)
                    .put("expectedSavedData",context.getString(R.string.peer_cached_data))
                    .put("expectedUnknownContact",context.getString(R.string.peer_last_online,context.getString(R.string.remote_unknown)))
                    .put("outgoingContact",text(column.findViewWithTag("profile_last_online:"+sub.value())).trim())
                    .put("incomingContact",text(column.findViewWithTag("profile_last_online:"+dom.value())).trim())
                    .put("textBounds",rows);
            try(FileOutputStream output=new FileOutputStream(new File(evidence,language+"-render.json"))){
                output.write(layout.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch(Exception failure){throw new AssertionError("Native component identity/locale witness could not be retained",failure);}
        assertTrue("Fixture column is no longer the host's current content",current);
        assertTrue(column.isAttachedToWindow());assertTrue(column.isShown());assertTrue(column.isLaidOut());
        assertFalse("Native component layout is still pending",column.isLayoutRequested());
        assertTrue(column.getWidth()>0 && column.getHeight()>0);
        assertEquals(requested.toLanguageTag(),actual.toLanguageTag());
        TextView saved=column.findViewWithTag("profile_cached_data:"+sub.value());assertNotNull(saved);
        assertEquals(context.getString(R.string.peer_cached_data),saved.getText().toString());
        TextView unknown=column.findViewWithTag("profile_last_online:"+dom.value()+"_unknown");assertNotNull(unknown);
        assertEquals(context.getString(R.string.peer_last_online,context.getString(R.string.remote_unknown)),unknown.getText().toString());
        Bitmap component=Bitmap.createBitmap(column.getWidth(),column.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(component);canvas.drawColor(context.getColor(R.color.background));column.draw(canvas);
            assertSame(column.getParent(),content.getChildAt(0));
            assertEquals("Native draw must retain the checked locale-bound content",copy,text(column));
            try(FileOutputStream output=new FileOutputStream(new File(evidence,language+"-render.png"))){
                assertTrue(component.compress(Bitmap.CompressFormat.PNG,100,output));
            }
        } catch(java.io.IOException failure){throw new AssertionError("Native component render could not be retained",failure);}
        finally{component.recycle();}
    }
    private static void assertTextFits(View view) {
        if(presenceCopy(view)) {
            TextView text=(TextView)view;assertNotNull(text.getLayout());assertTrue(text.getWidth()>0);
            for(int i=0;i<text.getLayout().getLineCount();i++)assertTrue("Last online text clipped",
                    text.getLayout().getLineMax(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1);
            assertTrue(text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1);
        }
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)assertTextFits(((android.view.ViewGroup)view).getChildAt(i));
    }
    private static void retainTextBounds(View root,File file) {
        try {
            JSONArray rows=new JSONArray();collectTextBounds(root,rows);
            try(FileOutputStream output=new FileOutputStream(file)){output.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        } catch(Exception failure){throw new AssertionError("Native text bounds could not be retained",failure);}
    }
    private static void collectTextBounds(View view,JSONArray rows) throws Exception {
        if(presenceCopy(view)) {
            TextView text=(TextView)view;JSONArray lines=new JSONArray();
            if(text.getLayout()!=null)for(int i=0;i<text.getLayout().getLineCount();i++)lines.put(new JSONObject()
                    .put("fullWidth",text.getLayout().getLineWidth(i)).put("visibleWidth",text.getLayout().getLineMax(i)));
            rows.put(new JSONObject().put("tag",view.getTag()).put("text",text.getText().toString())
                    .put("locale",text.getResources().getConfiguration().getLocales().get(0).toLanguageTag())
                    .put("viewIdentity",System.identityHashCode(view)).put("width",text.getWidth())
                    .put("available",text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight())
                    .put("height",text.getHeight()).put("lines",lines));
        }
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)collectTextBounds(((android.view.ViewGroup)view).getChildAt(i),rows);
    }
    private static boolean presenceCopy(View view) {
        if(!(view instanceof TextView)||!(view.getTag() instanceof String))return false;
        String tag=(String)view.getTag();return tag.startsWith("profile_last_online:")||tag.startsWith("profile_cached_data:");
    }
    private static String text(View view) {
        StringBuilder result=new StringBuilder();if(view instanceof TextView)result.append(((TextView)view).getText()).append('\n');
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)result.append(text(((android.view.ViewGroup)view).getChildAt(i)));
        return result.toString();
    }
    private static PersonSnapshot snapshot(PersonId id,PersonSnapshot.Connection state,long contact,PersonSnapshot.Freshness freshness) {
        return new PersonSnapshot(id,"Saved Sub","Saved device","Saved contract","Last observed: inactive",state,freshness,contact,
                EnumSet.of(PersonSnapshot.Capability.VIEW_STATS),Collections.emptyList(),Collections.emptyList(),"EUR",7200,7,0,3,false);
    }
    private static PersonId remote(char c){return new PersonId("remote_"+new String(new char[64]).replace('\0',c)+"_"+UUID.randomUUID());}
}
