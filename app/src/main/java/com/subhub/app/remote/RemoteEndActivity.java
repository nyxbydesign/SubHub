package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import org.json.*;

/** Command receipts and observed runtime remain separate throughout a targeted End request. */
public final class RemoteEndActivity extends RemotePage {
    private String expected;private boolean started;
    private Display displayed;
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,1500);}};
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.remote_end_title);
        if(!selectPeer()||!mayEdit()){finish();return;}
        expected=getIntent().getStringExtra("expectedSession");if(expected==null||!expected.matches("[0-9a-f]{64}")){finish();return;}
        started=state!=null&&state.getBoolean("started");if(!started)request();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("started",started);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(peer!=null&&expected!=null)clock.post(poll);}
    @Override protected void onPause(){clock.removeCallbacks(poll);super.onPause();}
    private void request(){if(!mayEdit())return;started=true;authorize(()->feature.endSession(peer,expected).whenComplete((id,error)->runOnUiThread(()->{
        if(isDestroyed()||isFinishing())return;if(error!=null){started=false;notice(R.string.remote_failure);}render();
    })));}
    private void render() {
        if (!mayEdit()) { finish(); return; }
        Display next = readDisplay();
        if (next.equals(displayed)) return;
        body.removeAllViews();
        text(body, next.name(), true);
        if (next.receipt() != null) text(body, next.receipt(), next.matchingReceipt());
        if (next.retry()) button(body, R.string.role_retry, this::request);
        if (next.observation() != null) text(body, next.observation(), false);
        if (next.runtime() != null) text(body, next.runtime(), false);
        if (next.diagnostic() != null) text(body, next.diagnostic(), false);
        button(body, R.string.remote_sync_now, feature::changed);
        displayed = next;
    }

    private Display readDisplay() {
        String receipt = null, observation = null, runtimeText = null, diagnostic = null;
        boolean matchingReceipt = false, retry = false;
        try {
            JSONObject latest = feature.endCommands.latest(peer);
            if (latest != null && expected.equals(latest.getJSONObject("request").getString("sessionIdentity"))) {
                receipt = getString(switch (latest.getString("state")) {
                    case "ENDED" -> R.string.remote_target_ended;
                    case "DISPATCHED" -> R.string.remote_end_dispatched;
                    case "EXPIRED" -> R.string.remote_end_expired;
                    case "REJECTED" -> R.string.remote_end_rejected;
                    case "SENT" -> R.string.remote_end_received;
                    default -> R.string.remote_end_requested;
                });
                matchingReceipt = true;
            } else {
                receipt = getString(R.string.remote_end_requested);
                retry = !started;
            }
            JSONObject runtime = feature.store.mirrored(peer.identity, peer.generation,
                    RemoteProtocol.Stream.RUNTIME, "current");
            observation = observed(RemoteProtocol.Stream.RUNTIME);
            runtimeText = RemoteTelemetry.validRuntime(runtime)
                    ? getString(R.string.remote_last_observed,
                            getString(runtime.getBoolean("serviceActive") ? R.string.remote_active : R.string.remote_inactive))
                    : getString(R.string.remote_unknown);
        } catch (JSONException invalid) {
            diagnostic = getString(R.string.remote_incomplete);
        }
        return new Display(peer.name, receipt, matchingReceipt, retry, observation, runtimeText, diagnostic);
    }

    private record Display(String name, String receipt, boolean matchingReceipt, boolean retry,
            String observation, String runtime, String diagnostic) {}
}
