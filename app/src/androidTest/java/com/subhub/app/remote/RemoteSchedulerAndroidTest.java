package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.app.job.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RemoteSchedulerAndroidTest {
    @Test public void actualNetworkJobSchedulesAndRunsThePeerWorker()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE));
        WorkManager manager=WorkManager.getInstance(context);manager.cancelUniqueWork(RemoteScheduler.PERIODIC).getResult().get(5,TimeUnit.SECONDS);
        SharedPreferences receipt=context.getSharedPreferences("subhub_peer_worker",0);long prior=receipt.getLong("last_started",0);
        RemoteScheduler.synchronize(context,true);
        try {
            long deadline=SystemClock.uptimeMillis()+25_000;WorkInfo selected=null;JobInfo observed=null;boolean forced=false;
            while(SystemClock.uptimeMillis()<deadline) {
                for(WorkInfo value:manager.getWorkInfosForUniqueWork(RemoteScheduler.PERIODIC).get(5,TimeUnit.SECONDS))if(!value.getState().isFinished())selected=value;
                if(selected!=null) {
                    assertEquals(NetworkType.CONNECTED,selected.getConstraints().getRequiredNetworkType());
                    for(JobInfo job:context.getSystemService(JobScheduler.class).getAllPendingJobs())if(selected.getId().toString().equals(job.getExtras().getString("EXTRA_WORK_SPEC_ID")))observed=job;
                    if(receipt.getLong("last_started",0)>prior && selected.getId().toString().equals(receipt.getString("last_work_id","")) && receipt.getLong("last_finished",0)>=receipt.getLong("last_started",0))break;
                    if(observed!=null&&!forced){forced=true;shell("cmd jobscheduler run -f "+context.getPackageName()+" "+observed.getId());}
                }
                Thread.sleep(100);
            }
            assertNotNull(selected);assertTrue("Scheduled worker did not start",receipt.getLong("last_started",0)>prior);
            assertEquals(selected.getId().toString(),receipt.getString("last_work_id",""));assertTrue(receipt.getBoolean("last_complete",false));
            if(observed!=null)assertNotEquals(JobInfo.NETWORK_TYPE_NONE,observed.getNetworkType());
            String logs=shell("logcat -d -v brief -s WM-SystemJobService");String witness=null;
            for(String line:logs.split("\n"))if(line.contains("onStartJob")&&line.contains(selected.getId().toString()))witness=line;
            assertNotNull("Missing actual SystemJobService start",witness);
            org.json.JSONObject evidence=new org.json.JSONObject().put("work",selected.getId().toString()).put("job",observed==null?org.json.JSONObject.NULL:observed.getId()).put("osStart",witness).put("started",receipt.getLong("last_started",0)).put("finished",receipt.getLong("last_finished",0));
            File directory=new File(context.getFilesDir(),"remote-job-evidence");directory.mkdirs();try(OutputStream output=new FileOutputStream(new File(directory,selected.getId()+".json"))){output.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }finally{manager.cancelUniqueWork(RemoteScheduler.PERIODIC).getResult().get(5,TimeUnit.SECONDS);manager.cancelUniqueWork(RemoteScheduler.IMMEDIATE).getResult().get(5,TimeUnit.SECONDS);}
    }
    private static String shell(String command)throws IOException {
        try(ParcelFileDescriptor result=InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).executeShellCommand(command);InputStream input=new FileInputStream(result.getFileDescriptor())) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toString("UTF-8");
        }
    }
}
