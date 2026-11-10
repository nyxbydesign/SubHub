package com.subhub.app.journal;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.remote.IrohPeerTransport;
import com.subhub.app.remote.PairingInvite;
import com.subhub.app.remote.PeerTransport;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteSession;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;

/** Real sender publication and a current-generation recipient view, including explicit re-sharing. */
public final class JournalCurrentAudienceAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private PeerTransport dt, st;
    private RemoteStore dom, sub;
    private JournalRuntime dr, sr, previous;
    private RemoteStore.Peer dp, sp;
    private ExecutorService serving;
    private Field singleton;
    private File root;
    private String dn, sn, dj, sj;
    @Before public void setup() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468")); unlockDom();
        root = new File(context.getCacheDir(), "journal-current-" + UUID.randomUUID()); assertTrue(root.mkdir());
        dn = "journal-current-dom-" + UUID.randomUUID(); sn = "journal-current-sub-" + UUID.randomUUID();
        dj = "journal-current-dj-" + UUID.randomUUID(); sj = "journal-current-sj-" + UUID.randomUUID();
        dom = new RemoteStore(context, dn); sub = new RemoteStore(context, sn);
        dt = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        st = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        dr = new JournalRuntime(context, dom, dj, new File(root, "dom"));
        sr = new JournalRuntime(context, sub, sj, new File(root, "sub"));
        singleton = JournalRuntime.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (JournalRuntime) singleton.get(null);
        serving = Executors.newSingleThreadExecutor(); pair();
    }
    private static byte[] seed() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes; }
    private void unlockDom() { assertTrue(ControllerPinManager.verify(context, "2468")); ControllerPinManager.enterDomMode(); }
    private static Object storage(RemoteStore store, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RemoteStore.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(store, args);
    }
    private void pair() throws Exception {
        unlockDom(); PairingInvite invite = PairingInvite.create(dt.localIdentity(), dt.inviteRoute(), System.currentTimeMillis());
        storage(dom,"saveInvite",new Class<?>[]{PairingInvite.class},invite);
        storage(dom,"bindInvite",new Class<?>[]{String.class,String.class,String.class,long.class},invite.id,st.localIdentity(),invite.tokenHash(),System.currentTimeMillis());
        storage(dom,"describeCandidate",new Class<?>[]{String.class,String.class,String.class,String.class},invite.id,st.localIdentity(),"Synthetic Sub",st.inviteRoute());
        storage(dom,"approveInvite",new Class<?>[]{String.class,String.class,String.class,long.class},invite.id,st.localIdentity(),"Synthetic Sub",System.currentTimeMillis());
        storage(sub,"acceptInvite",new Class<?>[]{PairingInvite.class,String.class},invite,"Synthetic Dom");
        storage(dom,"activate",new Class<?>[]{String.class,String.class},st.localIdentity(),invite.id);
        storage(sub,"activate",new Class<?>[]{String.class,String.class},dt.localIdentity(),invite.id);
        dp=dom.peer(st.localIdentity(),invite.id);sp=sub.peer(dt.localIdentity(),invite.id);
        assertEquals(RemoteStore.State.ACTIVE,dp.state);assertEquals(RemoteStore.State.ACTIVE,sp.state);
    }
    private RemoteSession.Hooks hooks(JournalRuntime runtime) throws Exception {
        RemoteAssets assets=new RemoteAssets(new File(root,runtime==dr?"empty-dom":"empty-sub"));
        return new RemoteSession.Hooks(){
            public int journalVersion(){return 1;}
            public void journal(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline)throws Exception {JournalPeer.exchange(runtime,connection,peer,deadline);}
            public void prepare(RemoteStore.Peer peer){}
            public void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream){}
            public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer){return List.of();}
            public RemoteAssets assets(RemoteStore.Peer peer){return assets;}
            public void complete(RemoteStore.Peer peer){}
        };
    }
    private void exchange() throws Exception {
        RemoteSession.Hooks dh=hooks(dr),sh=hooks(sr);
        Future<?> receiver=serving.submit(()->{try(PeerTransport.Connection c=st.accept(10_000)){
            JSONObject hello=RemoteProtocol.read(c.input());assertEquals(dt.localIdentity(),c.authenticatedPeerIdentity());
            new RemoteSession(sub,st,sh).incoming(c,hello,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
        }catch(Exception failure){throw new RuntimeException(failure);}});
        try(PeerTransport.Connection c=dt.connect(st.localIdentity(),st.inviteRoute(),10_000)){
            assertEquals(st.localIdentity(),c.authenticatedPeerIdentity());
            new RemoteSession(dom,dt,dh).outgoing(c,dp,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
        }
        receiver.get(35,TimeUnit.SECONDS);
    }
    @Test public void currentDomReceivesFirstShareAndOldAudienceRequiresExplicitCurrentShare() throws Exception {
        JournalEntry entry=new JournalEntry(UUID.randomUUID().toString().replace("-",""),System.currentTimeMillis(),System.currentTimeMillis(),"Synthetic shared Sub journal",null,List.of());
        ControllerPinManager.enterSubMode();sr.save(false,entry,true,true);
        assertTrue(sr.entries.audience(false,entry.id).contains(JournalRuntime.scope(sp)));assertFalse(sr.needsCurrentSharing(false,entry.id));
        unlockDom();exchange();assertEquals(entry.id,dr.list(true,RemotePersonRepository.id(dp),0).get(0).id);
        String oldScope=JournalRuntime.scope(sp);String oldGeneration=sp.generation;
        storage(dom,"revoke",new Class<?>[]{String.class,String.class},dp.identity,dp.generation);storage(sub,"revoke",new Class<?>[]{String.class,String.class},sp.identity,sp.generation);pair();
        ControllerPinManager.enterSubMode();assertTrue(sr.shared(false,entry.id));assertTrue(sr.needsCurrentSharing(false,entry.id));
        assertTrue(sr.entries.audience(false,entry.id).contains(oldScope));assertFalse(sr.entries.audience(false,entry.id).contains(JournalRuntime.scope(sp)));
        unlockDom();exchange();assertTrue(dr.list(true,RemotePersonRepository.id(dp),0).isEmpty());
        ControllerPinManager.enterSubMode();singleton.set(null,sr);
        try(ActivityScenario<JournalActivity> page=launch(false,PersonId.LOCAL)){
            awaitTag(page,"journal_entry:"+entry.id);tap("journal_entry:"+entry.id);awaitTag(page,"journal_share_current");awaitTag(page,"journal_current_audience");
            tap("journal_share_current");await(()->sr.entries.audience(false,entry.id).contains(JournalRuntime.scope(sp)));
        }
        ControllerPinManager.enterSubMode();assertFalse(sr.needsCurrentSharing(false,entry.id));
        unlockDom();exchange();assertEquals(entry.text,dr.list(true,RemotePersonRepository.id(dp),0).get(0).text);
        assertNotEquals(oldGeneration,dp.generation);singleton.set(null,dr);
        try(ActivityScenario<JournalActivity> page=launch(true,RemotePersonRepository.id(dp))){
            awaitTag(page,"journal_entry:"+entry.id);tap("journal_entry:"+entry.id);awaitTag(page,"journal_read_only");
            page.onActivity(a->{TextView body=a.findViewById(android.R.id.content).findViewWithTag("journal_text_body");assertEquals(entry.text,body.getText().toString());assertNull(a.findViewById(android.R.id.content).findViewWithTag("journal_share_current"));});
        }
    }
    private ActivityScenario<JournalActivity> launch(boolean dom,PersonId target){return ActivityScenario.launch(new Intent(context,JournalActivity.class).putExtra(JournalRoutes.EXTRA_DOM,dom).putExtra(PersonRoutes.EXTRA_PERSON_ID,target.value()));}
    private void tap(String tag){onView(withTagValue(is(tag))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());}
    private interface Condition{boolean test()throws Exception;}
    private void await(Condition condition)throws Exception{long deadline=SystemClock.uptimeMillis()+12_000;while(SystemClock.uptimeMillis()<deadline){if(condition.test())return;Thread.sleep(40);}throw new AssertionError("Journal fixture did not settle");}
    private void awaitTag(ActivityScenario<JournalActivity> page,String tag)throws Exception{await(()->{AtomicBoolean found=new AtomicBoolean();page.onActivity(a->{View v=a.findViewById(android.R.id.content).findViewWithTag(tag);found.set(v!=null&&v.isShown()&&v.getHeight()>0);});return found.get();});}
    @After public void cleanup()throws Exception{
        if(singleton!=null)singleton.set(null,previous);if(serving!=null){serving.shutdownNow();serving.awaitTermination(5,TimeUnit.SECONDS);}
        if(dt!=null)dt.close();if(st!=null)st.close();if(dr!=null)dr.entries.close();if(sr!=null)sr.entries.close();if(dom!=null)dom.close();if(sub!=null)sub.close();
        for(String n:new String[]{dn,sn,dj,sj})if(n!=null)context.deleteDatabase(n);
        if(root!=null&&root.exists()){assertTrue(root.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator));try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)){assertFalse(Files.isSymbolicLink(path));Files.delete(path);}}}
    }
}
