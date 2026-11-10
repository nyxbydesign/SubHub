package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.RemoteAppIcon;
import com.subhub.app.util.RemoteAppIcons;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Actual SQLite authority, actual module/cache and actual UI publication seam; no user inventory. */
public final class RemoteAppIconIntegrationAndroidTest extends LinkingFixture {
    static final String PACKAGE="com.example.recorded";
    private RemoteAppIconIntegration icons;
    private RemoteStore.Peer peer;
    private File cacheRoot;
    private String generation;
    private AutoCloseable installed;
    private RemoteFeature pageFeature;
    private RemotePersonRepository pageRepository;
    private Context pageContext;
    private final Map<Field,Object> pageGlobals=new LinkedHashMap<>();
    private final Set<String> pagePreferences=ConcurrentHashMap.newKeySet();
    private final ExecutorService iconWorker=Executors.newSingleThreadExecutor();

    /** Worker-created scope for actual page tests; all backend storage and cache paths are fixture-owned. */
    public static final class Fixture implements AutoCloseable {
        private final RemoteAppIconIntegrationAndroidTest owner=new RemoteAppIconIntegrationAndroidTest();
        private boolean closed;
        public Fixture()throws Exception{
            try{owner.setupFormalFixture();owner.setupIcons();}
            catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        public Context context(){return owner.pageContext==null?owner.context:owner.pageContext;}
        public RemoteStore store(){return owner.store;}
        public RemoteStore.Peer peer(){return owner.peer;}
        public com.subhub.app.people.PersonId personId(){return RemotePersonRepository.id(owner.peer);}
        public String packageName(){return PACKAGE;}
        public void enablePages()throws Exception{owner.enablePages();}
        public RemoteFeature feature(){if(owner.pageFeature==null)throw new IllegalStateException("enablePages required");return owner.pageFeature;}
        public RemotePersonRepository repository(){if(owner.pageRepository==null)throw new IllegalStateException("enablePages required");return owner.pageRepository;}
        public RemoteAppIcon identity(){return RemoteUi.appIcon(owner.context,owner.peer.identity,owner.peer.generation,PACKAGE);}
        /** Runs the actual DOM module/cache against bounded, scripted PNG frames for this recorded package. */
        public void offer()throws Exception{owner.receivePng(png());}
        public void retire(){owner.store.revoke(owner.peer.identity,owner.peer.generation);}
        public void refresh()throws Exception{owner.icons.refresh();}
        @Override public void close()throws Exception{
            if(closed)return;closed=true;
            try{owner.cleanupIcons();}finally{try{owner.cleanupLinkingFixture();}finally{owner.restoreFormalFixture();}}
        }
    }

    @Before public void setupIcons()throws Exception{
        await(provider().snapshot());
        PairingInvite invite=PairingInvite.create(DOM,"owned fixture",System.currentTimeMillis());generation=invite.id;
        store.saveInvite(invite);store.bindInvite(invite.id,SUB,invite.tokenHash(),System.currentTimeMillis());
        store.approveInvite(invite.id,SUB,"Fixture Sub",System.currentTimeMillis());store.activate(SUB,invite.id);
        peer=store.peer(SUB,generation);
        cacheRoot=new File(target.getCacheDir(),prefix+"icons");
        Context owned=new ContextWrapper(context){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getCacheDir(){return cacheRoot;}
        };
        icons=new RemoteAppIconIntegration(owned,store,iconWorker,()->{});
        store.onRevoked(icons::revoked);
        catalog(1,false);icons.refresh();
        installed=installIconProvider(icons);
    }

    @After public void cleanupIcons()throws Exception{
        try{
            try{if(installed!=null)installed.close();}
            finally{
                if(pageFeature!=null)cleanupPageFeature();
                else if(icons!=null)icons.close();
            }
        }finally{
            iconWorker.shutdownNow();assertTrue(iconWorker.awaitTermination(5,TimeUnit.SECONDS));
            if(cacheRoot!=null&&cacheRoot.exists()){
                Path root=cacheRoot.getCanonicalFile().toPath(),allowed=target.getCacheDir().getCanonicalFile().toPath();
                assertTrue(root.startsWith(allowed));assertEquals(prefix+"icons",root.getFileName().toString());
                try(java.util.stream.Stream<Path> paths=Files.walk(root)){
                    for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator){
                        assertFalse(Files.isSymbolicLink(path));assertTrue(path.toRealPath().startsWith(root));Files.delete(path);
                    }
                }
            }
        }
    }
    private void cleanupPageFeature()throws Exception{
        try{
            try{for(Map.Entry<Field,Object> entry:pageGlobals.entrySet())entry.getKey().set(null,entry.getValue());}
            finally{
                try{for(FormalRelationshipProvider provider:providers)provider.close();}
                finally{
                    try{pageFeature.shutdownForTest();}
                    finally{
                        try{pageFeature.activity.close();}
                        finally{
                            try{if(peer!=null)com.subhub.app.activity.ActivityNotifications.cancelPeer(pageContext,RemotePersonRepository.id(peer));}
                            finally{com.subhub.app.security.SubFeaturePolicy.unregisterHardcoreAuthority(pageContext,store);store.close();}
                        }
                    }
                }
            }
        }finally{
            Field ready=com.subhub.app.configuration.LegacyPackMigration.class.getDeclaredField("READY");ready.setAccessible(true);
            synchronized(com.subhub.app.configuration.LegacyPackMigration.class){((Set<?>)ready.get(null)).remove(pageContext.getFilesDir().getAbsoluteFile());}
            for(String name:pagePreferences)assertTrue(context.getSharedPreferences(name,0).edit().clear().commit());
        }
    }

    private void enablePages()throws Exception{
        if(pageFeature!=null)return;
        enableFeature();
        seedPageRecords();
        PersonSnapshot actual=pageRepository.load(RemotePersonRepository.id(peer));
        assertTrue(actual.sections.stream().anyMatch(section->!section.apps.isEmpty()));
        assertEquals(1,actual.allowances.size());assertEquals(47,actual.allowances.get(0).dailyMinutes);
        assertNotNull(actual.allowances.get(0).appIcon);assertNull(actual.allowances.get(0).appIcon.descriptor);
    }
    private void enableFeature()throws Exception{
        if(pageFeature!=null)return;
        assertTrue(ControllerPinManager.isDomModeActive());
        assertNull(icons.identity(SUB,generation,PACKAGE).descriptor);
        // No image phase is active. Retire the standalone publication before the real feature owns the callback.
        installed.close();installed=null;icons.close();
        pageContext=new ContextWrapper(context){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getCacheDir(){return cacheRoot;}
            @Override public File getFilesDir(){return ownedDirectory("files");}
            @Override public File getNoBackupFilesDir(){return ownedDirectory("no-backup");}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){pagePreferences.add(name);return super.getSharedPreferences(name,mode);}
            private File ownedDirectory(String name){File path=new File(cacheRoot,name);if(!path.isDirectory()&&!path.mkdirs())throw new IllegalStateException("Cannot create owned fixture directory");return path;}
        };
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(pageContext));
        saveGlobal(RemoteFeature.class,"instance");
        for(String name:new String[]{"remote","remoteAuthority","connectionUi","localProjection","keyholder"})saveGlobal(com.subhub.app.people.People.class,name);
        saveGlobal(com.subhub.app.relationships.RelationshipUi.class,"provider");
        saveGlobal(com.subhub.app.activity.ActivitySources.class,"repository");
        pageFeature=new RemoteFeature(pageContext,store,new RemoteSecrets(pageContext,"owned-secrets","subhub.test.formal."+prefix));
        assertTrue(pageFeature.recoveryReady());
        pageRepository=new RemotePersonRepository(pageContext,pageFeature);
        for(Field field:pageGlobals.keySet())if(field.getDeclaringClass()==RemoteFeature.class)field.set(null,pageFeature);
        com.subhub.app.people.People.installRemoteRepository(pageRepository);
        com.subhub.app.people.People.installRemoteAuthority(pageFeature::controlsLocal);
        com.subhub.app.people.People.installLocalProjection(pageRepository::localProjection);
        com.subhub.app.people.People.installKeyholder(RemoteUi.keyholderProvider(pageFeature));
        com.subhub.app.people.People.installConnectionUi(new RemoteUi());
        com.subhub.app.activity.ActivitySources.install(pageFeature.activity);
        com.subhub.app.relationships.RelationshipUi.install(providers.get(0));
        icons=pageFeature.appIcons;icons.refresh();installed=installIconProvider(icons);
    }

    private void saveGlobal(Class<?> owner,String name)throws Exception{
        Field field=owner.getDeclaredField(name);field.setAccessible(true);pageGlobals.put(field,field.get(null));
    }
    private void seedPageRecords()throws Exception{
        JSONObject sections=new JSONObject();
        for(String section:com.subhub.app.configuration.ConfigurationSchema.SECTIONS)
            sections.put(section,com.subhub.app.configuration.ConfigurationSchema.captureSection(pageContext,section));
        sections.getJSONObject("limits").put("app_timer_per_app_enabled",true).put("app_timer_total_enabled",false);
        long now=System.currentTimeMillis();
        JSONObject baseline=RemoteConfiguration.fromSections(RemoteConfiguration.currentMetadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Fixture contract",now,now),
                sections,new org.json.JSONArray(),new JSONObject().put("all",false).put("packages",new org.json.JSONArray().put(PACKAGE)).put("allowances",new JSONObject().put(PACKAGE,47)),
                new JSONObject().put("duration",3_600_000L).put("minimum",1_800_000L).put("maximum",86_400_000L).put("hidden",false));
        RemoteConfiguration.active(baseline);receive(RemoteProtocol.Stream.BASELINE,"active",baseline);
        receive(RemoteProtocol.Stream.APP_USAGE,"today",new JSONObject().put("schema",1).put("day",java.time.LocalDate.now().toString()).put("zone",java.time.ZoneId.systemDefault().getId()).put("millis",new JSONObject().put(PACKAGE,120_000L)));
        com.subhub.app.activity.ActivityEvent event=new com.subhub.app.activity.ActivityEvent("icon-page-open",com.subhub.app.activity.ActivityEvent.Kind.APP_OPENED,now,PACKAGE,0,"","",0);
        receive(RemoteProtocol.Stream.EVENTS,event.id,RemoteActivityWire.encode(event,java.time.ZoneId.systemDefault().getId(),""));
        JSONObject summary=new JSONObject().put("schema",1).put("currency","EUR");
        for(String key:new String[]{"protectedSeconds","serviceSeconds","sessions","activityEvents","censors","limitMillis","limitStops","whispers","popups","tamperEvents","tributes","assessedEUR","assessedUSD","paidEUR","paidUSD","currentSeconds","currentEvents","currentCensors","currentLimitMillis","currentLimitStops","currentWhispers","currentPopups","currentTributes","currentAssessedEUR","currentAssessedUSD"})summary.put(key,0);
        assertTrue(RemoteTelemetry.validSummary(summary));receive(RemoteProtocol.Stream.STATS,"summary",summary);
        JSONObject heads=new JSONObject();
        for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values())if(stream.writer==RemoteProtocol.Role.SUB)
            heads.put(stream.name(),stream==RemoteProtocol.Stream.BASELINE||stream==RemoteProtocol.Stream.APP_CATALOG||stream==RemoteProtocol.Stream.APP_USAGE||stream==RemoteProtocol.Stream.EVENTS||stream==RemoteProtocol.Stream.STATS?1:0);
        store.confirmHeads(SUB,generation,heads,true);icons.refresh();
    }
    private void receive(RemoteProtocol.Stream stream,String key,JSONObject body)throws Exception{
        long observed=stream==RemoteProtocol.Stream.EVENTS?RemoteActivityWire.decode(body).observedAt:System.currentTimeMillis();
        store.receive(SUB,new RemoteProtocol.Batch(generation,stream,0,1,Collections.singletonList(new RemoteProtocol.Change(1,key,body.toString(),false,observed))));
    }

    @Test public void descriptorArrivalReprojectsActualDtoAndRevokeRefusesOfflineBytes()throws Exception{
        RemoteAppIcon bare=RemoteUi.appIcon(context,SUB,generation,PACKAGE);
        assertNotNull(bare);assertNull(bare.descriptor);assertEquals(SUB,bare.peer);assertEquals(generation,bare.generation);
        CountDownLatch arrival=new CountDownLatch(1);AtomicReference<PersonSnapshot.App> projected=new AtomicReference<>();
        AtomicReference<Throwable> failed=new AtomicReference<>();
        try(AutoCloseable observer=RemoteUi.observeAppIcons(()->{
            try{
                assertEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());assertFalse(Thread.holdsLock(store));
                RemoteAppIcon identity=RemoteUi.appIcon(context,SUB,generation,PACKAGE);
                if(identity.descriptor!=null){projected.set(new PersonSnapshot.App(PACKAGE,"Recorded app",identity));arrival.countDown();}
            }catch(Throwable error){failed.set(error);arrival.countDown();}
        })){
            byte[] png=png();receivePng(png);
            assertTrue(arrival.await(10,TimeUnit.SECONDS));if(failed.get()!=null)throw new AssertionError(failed.get());
            RemoteAppIcon complete=projected.get().appIcon;assertNotNull(complete.descriptor);
            assertArrayEquals(png,icons.load(complete));
            icons.refresh();assertEquals(complete,RemoteUi.appIcon(context,SUB,generation,PACKAGE));
            store.revoke(SUB,generation);
            assertFalse(icons.currentAuthority().permits(complete));assertNull(icons.load(complete));
            RemoteAppIcon revoked=RemoteUi.appIcon(context,SUB,generation,PACKAGE);
            assertEquals(SUB,revoked.peer);assertEquals(generation,revoked.generation);assertNull(revoked.descriptor);
            assertFalse(icons.current(peer));assertFalse(icons.mirrored(peer,PACKAGE));
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion=30)
    @Test public void recordRemovalAndDomLockCannotRenewCachedImageAuthority()throws Exception{
        enableFeature();
        icons.identity(SUB,generation,PACKAGE);receivePng(png());
        RemoteAppIcon complete=icons.identity(SUB,generation,PACKAGE);assertNotNull(complete.descriptor);
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AtomicReference<Throwable> callbackFailure=new AtomicReference<>();java.util.concurrent.atomic.AtomicInteger notices=new java.util.concurrent.atomic.AtomicInteger();
        try(AutoCloseable listener=RemoteUi.observeAppIcons(()->{
                try{assertEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());assertFalse(Thread.holdsLock(store));assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));assertFalse(Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK));notices.incrementAndGet();}
                catch(Throwable failure){callbackFailure.set(failure);}
            });androidx.test.core.app.ActivityScenario<com.subhub.app.help.HelpActivity> page=androidx.test.core.app.ActivityScenario.launch(com.subhub.app.help.HelpActivity.class)){
            AtomicReference<android.widget.ImageView> view=new AtomicReference<>();
            page.onActivity(activity->{android.widget.ImageView image=new android.widget.ImageView(activity);view.set(image);activity.setContentView(image);RemoteUi.bindAppIcon(image,complete,com.subhub.app.R.drawable.ic_settings_apps);});
            awaitImage(view,true);
            page.onActivity(activity->{int before=notices.get();ControllerPinManager.enterSubMode();assertEquals(before,notices.get());assertFalse(icons.currentAuthority().permits(complete));});
            awaitImage(view,false);assertNull(callbackFailure.get());assertNull(icons.load(complete));assertNull(icons.identity(SUB,generation,PACKAGE).descriptor);
            assertZeroProtectedReadsWhileViewerAbsent();
            assertTrue(ControllerPinManager.verify(context,"624871"));icons.refresh();receivePng(png());
            RemoteAppIcon restored=icons.identity(SUB,generation,PACKAGE);
            page.onActivity(activity->RemoteUi.bindAppIcon(view.get(),restored,com.subhub.app.R.drawable.ic_settings_apps));
            awaitImage(view,true);
            page.onActivity(activity->{
                int before=notices.get();
                assertTrue(com.subhub.app.security.DeviceRolePreferences.set(context,com.subhub.app.security.DeviceRolePreferences.Mode.SUB_ONLY));
                assertEquals(before,notices.get());
                assertTrue(ControllerPinManager.isDomModeActive());assertFalse(icons.currentAuthority().permits(restored));
            });
            awaitImage(view,false);assertNull(callbackFailure.get());assertNull(icons.load(restored));assertZeroProtectedReadsWhileViewerAbsent();
        }
        context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,"BOTH").commit();
        catalog(2,true);icons.refresh();
        assertFalse(icons.currentAuthority().permits(complete));assertFalse(icons.mirrored(peer,PACKAGE));assertNull(icons.load(complete));
        assertTrue(icons.wanted(peer).isEmpty());assertNull(icons.identity(SUB,generation,PACKAGE).descriptor);
    }
    private static void awaitImage(AtomicReference<android.widget.ImageView> view,boolean expected)throws Exception{
        long deadline=android.os.SystemClock.uptimeMillis()+10_000;
        while(android.os.SystemClock.uptimeMillis()<deadline){
            java.util.concurrent.atomic.AtomicBoolean matches=new java.util.concurrent.atomic.AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                android.widget.ImageView image=view.get();boolean bitmap=image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable;
                matches.set(image.isAttachedToWindow()&&bitmap==expected);
                if(matches.get()&&bitmap){
                    Bitmap pixels=((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap();int color=pixels.getPixel(pixels.getWidth()/2,pixels.getHeight()/2);
                    if(color!=Color.MAGENTA){
                        File retained=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"remote-icon-pixel-failure-"+UUID.randomUUID()+".png");
                        try(OutputStream output=new FileOutputStream(retained)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,output));}
                        catch(IOException failure){throw new AssertionError("Could not retain failed pixels",failure);}
                        fail("Unexpected attached pixels; retained "+retained.getAbsolutePath());
                    }
                }
            });
            if(matches.get())return;Thread.sleep(20);
        }
        fail("Attached image did not become "+(expected?"the remote bitmap":"the generic placeholder"));
    }
    private void assertZeroProtectedReadsWhileViewerAbsent()throws Exception{
        if(android.os.Build.VERSION.SDK_INT<30)throw new IllegalStateException("SQLite read spy requires API 30");
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        android.database.sqlite.SQLiteDatabase sql=store.getWritableDatabase();
        sql.setCustomScalarFunction("icon_guard_read",body->{reads.incrementAndGet();return body;});
        // A real SQLite read spy in this fresh-owned database, without subclassing Store or replacing authority.
        sql.execSQL("ALTER TABLE remote_objects RENAME TO icon_guard_objects");
        try{
            sql.execSQL("CREATE VIEW remote_objects AS SELECT peer,generation,stream,item,seq,icon_guard_read(body) AS body,deleted,observed FROM icon_guard_objects");
            try(android.database.Cursor calibration=sql.rawQuery("SELECT body FROM remote_objects LIMIT 1",null)){assertTrue(calibration.moveToFirst());assertNotNull(calibration.getString(0));}
            assertTrue(reads.get()>0);reads.set(0);
            Context noUiIo=new ContextWrapper(context){
                @Override public Context getApplicationContext(){return this;}
                @Override public File getCacheDir(){return new File(cacheRoot,"guard-cache");}
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());return super.getSharedPreferences(name,mode);}
            };
            try(RemoteAppIconIntegration guarded=new RemoteAppIconIntegration(noUiIo,store,iconWorker,()->{})){
                guarded.refresh();assertEquals(0,reads.get());
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse(guarded.currentAuthority().permits(new RemoteAppIcon(SUB,generation,PACKAGE,null))));
                assertEquals(0,reads.get());
            }
        }finally{
            sql.execSQL("DROP VIEW IF EXISTS remote_objects");sql.execSQL("ALTER TABLE icon_guard_objects RENAME TO remote_objects");
        }
    }

    @Test public void unreferencedPackageAndWrongGenerationNeverBecomeRequestsOrDescriptors()throws Exception{
        RemoteAppIcon absent=icons.identity(SUB,generation,"com.example.unrecorded");
        assertNotNull(absent);assertNull(absent.descriptor);assertTrue(icons.wanted(peer).isEmpty());
        String other=UUID.randomUUID().toString();RemoteAppIcon wrong=icons.identity(SUB,other,PACKAGE);
        assertEquals(other,wrong.generation);assertNull(wrong.descriptor);assertFalse(icons.currentAuthority().permits(wrong));
        assertTrue(icons.wanted(peer).isEmpty());assertFalse(icons.published(peer,PACKAGE));
        assertNull(icons.identity(SUB,generation,"invalid/package"));assertNull(RemoteUi.appIcon(context,SUB,generation,""));
        List<String> names=new ArrayList<>();
        for(int i=0;i<12;i++){
            String name="com.example.rotation"+i;names.add(name);long sequence=i+2;
            JSONObject body=new JSONObject().put("schema",1).put("package",name).put("label",name);
            store.receive(SUB,new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.APP_CATALOG,sequence-1,sequence,
                    Collections.singletonList(new RemoteProtocol.Change(sequence,name,body.toString(),false,System.currentTimeMillis()))));
        }
        icons.refresh();for(String name:names)icons.identity(SUB,generation,name);
        List<String> first=new ArrayList<>(names);Collections.reverse(first);first=new ArrayList<>(first.subList(0,8));
        byte[] image=png();List<JSONObject> replies=new ArrayList<>();
        for(int i=0;i<first.size();i++){
            String name=first.get(i);
            if(i%2==0){
                replies.add(described("icon_offer",image,name));
                replies.add(described("icon_chunk",image,name).put("offset",0).put("data",Base64.getEncoder().encodeToString(image)));
                replies.add(described("icon_end",image,name).put("offset",image.length));
            }else replies.add(iconFrame("icon_unavailable").put("package",name));
        }
        replies.add(iconFrame("icons_complete"));ByteArrayOutputStream output=new ByteArrayOutputStream();
        icons.exchange(connection(SUB,frames(replies.toArray(new JSONObject[0])),output),peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        JSONObject requested=RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(8,requested.getJSONArray("packages").length());
        for(int i=0;i<8;i++)assertEquals(first.get(i),requested.getJSONArray("packages").getString(i));
        assertNotNull(icons.identity(SUB,generation,first.get(0)).descriptor);assertNull(icons.identity(SUB,generation,first.get(1)).descriptor);
        List<String> second=icons.wanted(peer);assertEquals(8,second.size());Set<String> covered=new HashSet<>(first);covered.addAll(second);assertEquals(new HashSet<>(names),covered);
        RemoteAppIcon removed=icons.identity(SUB,generation,first.get(0));
        store.receive(SUB,new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.APP_CATALOG,13,14,
                Collections.singletonList(new RemoteProtocol.Change(14,removed.packageName,"{}",true,System.currentTimeMillis()))));
        icons.refresh();assertFalse(icons.currentAuthority().permits(removed));assertNull(icons.load(removed));assertFalse(icons.wanted(peer).contains(removed.packageName));
        for(int i=0;i<300;i++)icons.identity(SUB,generation,"com.example.unrecorded"+i);
        Field demand=RemoteAppIconIntegration.class.getDeclaredField("demand"),attempts=RemoteAppIconIntegration.class.getDeclaredField("lastAttempt");demand.setAccessible(true);attempts.setAccessible(true);
        Map<?,?> pending=(Map<?,?>)demand.get(icons),history=(Map<?,?>)attempts.get(icons);
        assertEquals(256,pending.size());assertTrue(pending.keySet().containsAll(history.keySet()));assertTrue(icons.wanted(peer).isEmpty());
    }

    private void catalog(long sequence,boolean deleted)throws Exception{
        JSONObject value=deleted?new JSONObject():new JSONObject().put("schema",1).put("package",PACKAGE).put("label","Recorded app");
        store.receive(SUB,new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.APP_CATALOG,sequence-1,sequence,
                Collections.singletonList(new RemoteProtocol.Change(sequence,PACKAGE,value.toString(),deleted,System.currentTimeMillis()))));
    }
    private void receivePng(byte[] bytes)throws Exception{
        icons.identity(SUB,generation,PACKAGE);
        JSONObject offer=described("icon_offer",bytes),chunk=described("icon_chunk",bytes).put("offset",0).put("data",Base64.getEncoder().encodeToString(bytes));
        JSONObject end=described("icon_end",bytes).put("offset",bytes.length);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        icons.exchange(connection(SUB,frames(offer,chunk,end,iconFrame("icons_complete")),output),peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        ByteArrayInputStream sent=new ByteArrayInputStream(output.toByteArray());
        JSONObject wanted=RemoteProtocol.read(sent);assertEquals("icon_wants",wanted.getString("type"));assertEquals(PACKAGE,wanted.getJSONArray("packages").getString(0));
        assertEquals("icon_resume",RemoteProtocol.read(sent).getString("type"));
        assertEquals("icon_ack",RemoteProtocol.read(sent).getString("type"));assertEquals("icon_ack",RemoteProtocol.read(sent).getString("type"));
        assertEquals("icons_ack",RemoteProtocol.read(sent).getString("type"));assertEquals(0,sent.available());
    }
    private JSONObject iconFrame(String type)throws Exception{return new JSONObject().put("version",1).put("type",type).put("generation",generation).put("appIconsVersion",1);}
    private JSONObject described(String type,byte[] bytes)throws Exception{return described(type,bytes,PACKAGE);}
    private JSONObject described(String type,byte[] bytes,String name)throws Exception{return iconFrame(type).put("package",name).put("format","png")
            .put("hash",RemoteProtocol.sha256(bytes)).put("length",bytes.length).put("width",32).put("height",32);}
    static byte[] png(){
        Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.MAGENTA);
        try{ByteArrayOutputStream output=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));return output.toByteArray();}
        finally{bitmap.recycle();}
    }

    /** Parent native page fixtures may reuse this exact provider installation without reinstalling app owners. */
    static AutoCloseable installIconProvider(RemoteAppIconIntegration provider)throws Exception{
        Field source=RemoteUi.class.getDeclaredField("iconProvider"),binder=RemoteUi.class.getDeclaredField("iconBinder"),callback=RemoteUi.class.getDeclaredField("iconChanged");
        source.setAccessible(true);binder.setAccessible(true);callback.setAccessible(true);
        Object previous=source.get(null),previousBinder=binder.get(null);Runnable changed=(Runnable)callback.get(null);
        AtomicReference<RemoteAppIcons> owned=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try{source.set(null,provider);owned.set(new RemoteAppIcons(provider));binder.set(null,owned.get());provider.addInvalidationListener(changed);}
            catch(IllegalAccessException failure){throw new AssertionError(failure);}
        });
        return ()->{
            provider.removeInvalidationListener(changed);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                owned.get().close();try{source.set(null,previous);binder.set(null,previousBinder);}catch(IllegalAccessException failure){throw new AssertionError(failure);}
            });
        };
    }
}
