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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
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
        PersonId sub=remote('b'),dom=remote('a');
        long old=java.time.LocalDateTime.of(2001,2,3,23,7,9).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        try(ActivityScenario<HelpActivity> scenario=ActivityScenario.launch(HelpActivity.class)) {
            for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.GERMAN}) {
                AtomicReference<LinearLayout> rendered=new AtomicReference<>();CountDownLatch frame=new CountDownLatch(1);
                scenario.onActivity(activity->{
                    Configuration configuration=new Configuration(target.getResources().getConfiguration());configuration.setLocale(locale);configuration.fontScale=1.4f;
                    Context context=new androidx.appcompat.view.ContextThemeWrapper(target.createConfigurationContext(configuration),R.style.Theme_SubHub);
                    LinearLayout column=PersonUi.column(context);column.setPadding(PersonUi.dp(context,16),0,PersonUi.dp(context,16),0);
                    ScrollView scroll=new ScrollView(activity);scroll.addView(column);activity.setContentView(scroll);rendered.set(column);
                    PersonSnapshot saved=snapshot(sub,PersonSnapshot.Connection.OFFLINE,old,PersonSnapshot.Freshness.STALE)
                            .withProfile(new ProfileData("Saved Sub","","","Fixture gender",29),Collections.emptyMap());
                    ProfileViews.person(column,saved,()->{});
                    PersonUi.text(column,context.getString(R.string.role_last_online,ProfileViews.formatLastSeen(old)),false)
                            .setTag("profile_last_online:connection");
                    LinearLayout outgoing=PersonUi.column(context);outgoing.setTag("outgoing_sub_profile");column.addView(outgoing);
                    ProfileViews.identity(outgoing,saved);
                    LinearLayout counterpart=PersonUi.card(column);counterpart.setTag("incoming_dom_profile");
                    PersonSnapshot local=snapshot(PersonId.LOCAL,PersonSnapshot.Connection.LOCAL,System.currentTimeMillis()-60_000L,PersonSnapshot.Freshness.CURRENT)
                            .withContractDom(new ProfileData("Saved Dom","","","Fixture gender",37,true),dom,
                                    new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.OFFLINE,old));
                    ContractHeader.add(counterpart,local,false,ignored->{});
                    ProfileViews.person(column,snapshot(new PersonId(sub.value()+"_unknown"),PersonSnapshot.Connection.OFFLINE,0,PersonSnapshot.Freshness.UNKNOWN),()->{});
                    ProfileViews.person(column,snapshot(new PersonId(sub.value()+"_future"),PersonSnapshot.Connection.OFFLINE,
                            System.currentTimeMillis()+3_600_000,PersonSnapshot.Freshness.UNKNOWN),()->{});
                    ProfileViews.lastOnline(column,new PersonId(dom.value()+"_unknown"),0);
                    ProfileViews.lastOnline(column,new PersonId(dom.value()+"_future"),System.currentTimeMillis()+3_600_000);
                    ProfileViews.lastOnline(column,PersonId.LOCAL,old);
                    LinearLayout pending=PersonUi.card(column);pending.setTag("pending_dom_profile");
                    ContractHeader.add(pending,local.withContractDom(local.contractDom,dom,
                            new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.PENDING,0)),false,ignored->{});
                    LinearLayout revoked=PersonUi.card(column);revoked.setTag("revoked_dom_profile");
                    ContractHeader.add(revoked,local.withContractDom(local.contractDom,dom,
                            new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.REVOKED,old)),false,ignored->{});
                    LinearLayout replaced=PersonUi.card(column);replaced.setTag("replacement_dom_profile");
                    ContractHeader.add(replaced,local.withContractDom(local.contractDom,new PersonId(dom.value()+"_replacement")),false,ignored->{});
                    LinearLayout future=PersonUi.card(column);future.setTag("future_dom_profile");
                    ContractHeader.add(future,local.withContractDom(local.contractDom,dom,
                            new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.OFFLINE,System.currentTimeMillis()+3_600_000)),false,ignored->{});
                    LinearLayout localProfile=PersonUi.column(context);localProfile.setTag("local_sub_profile");column.addView(localProfile);
                    ProfileViews.identity(localProfile,local);
                    LinearLayout localDom=PersonUi.card(column);localDom.setTag("local_dom_profile");
                    ContractHeader.add(localDom,local.withContractDom(ProfileData.empty("Local Dom"),PersonId.LOCAL),false,ignored->{});
                    for(String alias:new String[]{"journal_local_dom","journal_local_sub"}) {
                        PersonId journal=new PersonId(alias);
                        LinearLayout journalDom=PersonUi.card(column);journalDom.setTag("journal_contract:"+alias);
                        ContractHeader.add(journalDom,local.withContractDom(ProfileData.empty("Local profile"),journal,
                                new PersonSnapshot.PeerContact(journal,PersonSnapshot.Connection.OFFLINE,old)),false,ignored->{});
                        PersonSnapshot journalPerson=snapshot(journal,PersonSnapshot.Connection.LOCAL,old,PersonSnapshot.Freshness.CURRENT);
                        ProfileViews.person(column,journalPerson,()->{});
                        LinearLayout journalProfile=PersonUi.column(context);journalProfile.setTag("journal_profile:"+alias);column.addView(journalProfile);
                        ProfileViews.identity(journalProfile,journalPerson);
                        ProfileViews.lastOnline(column,journal,old);
                    }
                    addOwnMetadataFixtures(column);
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
                    String observed=lastOnline(context,old);
                    String label=locale.equals(Locale.GERMAN)?"Zuletzt gesehen":"Last seen";
                    assertEquals(label+": 03.02.2001 23:07:09",observed);
                    assertEquals(label+": 03.02.2001 23:07:09",
                            ((TextView)column.findViewWithTag("profile_last_online:connection")).getText().toString());
                    assertEquals("Offline · "+label+" 03.02.2001 23:07:09",PersonUi.connection(context,
                            snapshot(sub,PersonSnapshot.Connection.OFFLINE,old,PersonSnapshot.Freshness.STALE)));
                    String offlineUnknown="Offline · "+label+(locale.equals(Locale.GERMAN)?" unbekannt":" unknown");
                    assertEquals(offlineUnknown,context.getString(R.string.role_offline_last_online_unknown));
                    for(long invalid:new long[]{0,-1,System.currentTimeMillis()+3_600_000})
                        assertEquals(offlineUnknown,PersonUi.connection(context,
                                snapshot(sub,PersonSnapshot.Connection.OFFLINE,invalid,PersonSnapshot.Freshness.UNKNOWN)));
                    View outgoing=column.findViewWithTag("outgoing_sub_profile"),incoming=column.findViewWithTag("incoming_dom_profile");
                    ProfileData subProfile=new ProfileData("Saved Sub","","","Fixture gender",29);
                    ProfileData domProfile=new ProfileData("Saved Dom","","","Fixture gender",37,true);
                    assertInline(column,"roster:"+sub.value(),sub,observed,subProfile);
                    assertEquals(context.getString(R.string.role_connection_offline),
                            ((TextView)column.findViewWithTag("roster:"+sub.value()+"_connection")).getText().toString());
                    assertInline(outgoing,"person",sub,observed,subProfile);
                    assertInline(incoming,"contract_dom",dom,observed,domProfile);
                    assertNotNull(column.findViewWithTag("profile_cached_data:"+sub.value()));
                    String unknown=context.getString(R.string.peer_last_online,context.getString(R.string.remote_unknown));
                    assertEquals(unknown,((TextView)column.findViewWithTag("profile_last_online:"+dom.value()+"_unknown")).getText().toString());
                    assertEquals(unknown,((TextView)column.findViewWithTag("profile_last_online:"+dom.value()+"_future")).getText().toString());
                    assertNull(column.findViewWithTag("profile_last_online:"+PersonId.LOCAL.value()));
                    for(String suffix:new String[]{"_unknown","_future"}) {
                        PersonId targetId=new PersonId(sub.value()+suffix);
                        assertInline(column,"roster:"+targetId.value(),targetId,unknown,ProfileData.empty("Saved Sub"));
                    }
                    View pending=column.findViewWithTag("pending_dom_profile"),revoked=column.findViewWithTag("revoked_dom_profile");
                    assertInline(pending,"contract_dom",dom,unknown,domProfile);
                    assertInline(revoked,"contract_dom",dom,observed,domProfile);
                    assertInline(column.findViewWithTag("replacement_dom_profile"),"contract_dom",new PersonId(dom.value()+"_replacement"),unknown,domProfile);
                    assertInline(column.findViewWithTag("future_dom_profile"),"contract_dom",dom,unknown,domProfile);
                    assertEmptyDetails(column.findViewWithTag("local_sub_profile"),"person");
                    assertEmptyDetails(column.findViewWithTag("local_dom_profile"),"contract_dom");
                    for(String alias:new String[]{"journal_local_dom","journal_local_sub"}) {
                        assertEmptyDetails(column.findViewWithTag("journal_contract:"+alias),"contract_dom");
                        assertEmptyDetails(column,"roster:"+alias);
                        assertEmptyDetails(column.findViewWithTag("journal_profile:"+alias),"person");
                        assertNull(column.findViewWithTag("profile_last_online:"+alias));
                    }
                    assertOwnMetadata(column);
                    assertTrue(text(pending).contains(context.getString(R.string.role_connection_pending)));
                    assertTrue(text(revoked).contains(context.getString(R.string.role_connection_revoked)));
                    assertFalse(text(pending).contains(context.getString(R.string.role_connection_connected)));
                    assertFalse(text(revoked).contains(context.getString(R.string.role_connection_connected)));
                    assertTextFits(column);
                });
            }
        }
    }

    private static void addOwnMetadataFixtures(LinearLayout column) {
        Context context=column.getContext();ProfileData empty=ProfileData.empty("Own profile");
        for(boolean dom:new boolean[]{true,false}) {
            String side=dom?"dom":"sub";
            LinearLayout own=PersonUi.card(column);own.setTag("own_plain_"+side);
            own.addView(ProfilePreview.own(context,empty,dom),new LinearLayout.LayoutParams(-1,-2));
            LinearLayout overview=PersonUi.card(column);
            OverviewUi.identity(overview,empty,dom,"overview_plain_"+side);
        }
        ProfileData metadata=new ProfileData("Own details","","","Fixture gender",29);
        LinearLayout ownDetails=PersonUi.card(column);ownDetails.setTag("own_metadata");
        ownDetails.addView(ProfilePreview.own(context,metadata,true),new LinearLayout.LayoutParams(-1,-2));
        OverviewUi.identity(PersonUi.card(column),metadata,false,"overview_metadata");
        ProfileData switchProfile=new ProfileData("Own Switch","","","Fixture gender",37,true);
        LinearLayout ownSwitch=PersonUi.card(column);ownSwitch.setTag("own_switch");
        ownSwitch.addView(ProfilePreview.own(context,switchProfile,false),new LinearLayout.LayoutParams(-1,-2));
        OverviewUi.identity(PersonUi.card(column),switchProfile,true,"overview_switch");
    }

    private static void assertOwnMetadata(LinearLayout column) {
        for(String side:new String[]{"dom","sub"}) {
            assertEmptyDetails(column.findViewWithTag("own_plain_"+side),"self_profile");
            assertEmptyDetails(column,"overview_plain_"+side);
        }
        ProfileData metadata=new ProfileData("Own details","","","Fixture gender",29);
        assertMetadata(column.findViewWithTag("own_metadata"),"self_profile",metadata);
        assertMetadata(column,"overview_metadata",metadata);
        ProfileData switchProfile=new ProfileData("Own Switch","","","Fixture gender",37,true);
        assertMetadata(column.findViewWithTag("own_switch"),"self_profile",switchProfile);
        assertMetadata(column,"overview_switch",switchProfile);
    }

    private static void assertEmptyDetails(View root,String prefix) {
        TextView details=root.findViewWithTag(prefix+"_details");assertNotNull(details);
        assertEquals("",details.getText().toString());assertEquals(View.GONE,details.getVisibility());
    }

    private static void assertMetadata(View root,String prefix,ProfileData profile) {
        TextView details=root.findViewWithTag(prefix+"_details");assertNotNull(details);
        assertEquals(metadata(root.getContext(),profile),details.getText().toString());
        assertEquals(View.VISIBLE,details.getVisibility());
        assertEquals(profile.name,((TextView)root.findViewWithTag(prefix+"_name")).getText().toString());
    }

    private static void assertInline(View root,String prefix,PersonId target,String lastOnline,ProfileData profile) {
        assertNotNull(root);TextView details=root.findViewWithTag(prefix+"_details");assertNotNull(details);
        String existing=metadata(root.getContext(),profile);
        assertEquals(lastOnline+(existing.isEmpty()?"":" · "+existing),details.getText().toString());
        assertEquals(View.VISIBLE,details.getVisibility());
        assertEquals(profile.name,((TextView)root.findViewWithTag(prefix+"_name")).getText().toString());
        assertNull("Presence must share the existing details row",root.findViewWithTag("profile_last_online:"+target.value()));
    }

    private static String metadata(Context context,ProfileData profile) {
        List<String> details=new ArrayList<>();
        if(profile.isSwitch)details.add(context.getString(R.string.profile_role_switch));
        if(profile.age!=0)details.add(context.getResources().getQuantityString(R.plurals.profile_age_value,profile.age,profile.age));
        if(!profile.gender.isEmpty())details.add(profile.gender);
        return String.join(" · ",details);
    }

    private static String lastOnline(Context context,long observed) {
        return context.getString(R.string.peer_last_online,
                new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.ROOT).format(new java.util.Date(observed)));
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
                    .put("requestedFontScale",1.4).put("actualFontScale",context.getResources().getConfiguration().fontScale)
                    .put("host",activity.getClass().getName()).put("hostIdentity",System.identityHashCode(activity))
                    .put("columnIdentity",System.identityHashCode(column)).put("currentContent",current)
                    .put("attached",column.isAttachedToWindow()).put("shown",column.isShown())
                    .put("laidOut",column.isLaidOut()).put("layoutRequested",column.isLayoutRequested())
                    .put("width",column.getWidth()).put("height",column.getHeight()).put("actualText",copy)
                    .put("expectedSavedData",context.getString(R.string.peer_cached_data))
                    .put("expectedUnknownContact",context.getString(R.string.peer_last_online,context.getString(R.string.remote_unknown)))
                    .put("metadataOrder","Last online, optional Switch, age, gender")
                    .put("outgoingDetails",text(((View)column.findViewWithTag("outgoing_sub_profile")).findViewWithTag("person_details")).trim())
                    .put("rosterDetails",text(column.findViewWithTag("roster:"+sub.value()+"_details")).trim())
                    .put("incomingDetails",text(((View)column.findViewWithTag("incoming_dom_profile")).findViewWithTag("contract_dom_details")).trim())
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
        assertEquals(1.4f,context.getResources().getConfiguration().fontScale,0.001f);
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
        if(presenceCopy(view) && view.getVisibility()==View.VISIBLE) {
            TextView text=(TextView)view;assertNotNull(text.getLayout());assertTrue(text.getWidth()>0);
            for(int i=0;i<text.getLayout().getLineCount();i++) {
                assertTrue("Profile metadata text clipped",
                        text.getLayout().getLineMax(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1);
                assertEquals("Profile metadata must not be ellipsized",0,text.getLayout().getEllipsisCount(i));
            }
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
                    .put("visibility",text.getVisibility()).put("fontScale",text.getResources().getConfiguration().fontScale)
                    .put("locale",text.getResources().getConfiguration().getLocales().get(0).toLanguageTag())
                    .put("viewIdentity",System.identityHashCode(view)).put("width",text.getWidth())
                    .put("available",text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight())
                    .put("height",text.getHeight()).put("lines",lines));
        }
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)collectTextBounds(((android.view.ViewGroup)view).getChildAt(i),rows);
    }
    private static boolean presenceCopy(View view) {
        if(!(view instanceof TextView)||!(view.getTag() instanceof String))return false;
        String tag=(String)view.getTag();return tag.startsWith("profile_last_online:")||tag.startsWith("profile_cached_data:")
                ||tag.endsWith("_details")||tag.endsWith("_name");
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
