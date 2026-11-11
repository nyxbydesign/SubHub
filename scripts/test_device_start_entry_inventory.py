"""Source-route checks only; these do not qualify Android callbacks or backend installation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/subhub/app'


def body(source, signature):
    start = source.index('{', source.index(signature))
    depth, end = 1, start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start + 1:end - 1]


class DeviceStartEntryInventoryTest(unittest.TestCase):
    def setUp(self):
        self.home = (JAVA / 'MainActivity.java').read_text()
        self.flow = (JAVA / 'appmode/DeviceStartViewModel.java').read_text()
        self.registry = (JAVA / 'appmode/DeviceStartResultRegistry.java').read_text()

    def test_installed_start_returns_before_all_legacy_effects(self):
        start = body(self.home, 'private void toggleProtection(')
        installed = body(start, 'if (gateway != null)')
        self.assertIn('deviceStart.begin(gateway, new AppRuntimeGateway.DeviceStartSpec(', installed)
        self.assertIn('return;', installed)
        for old in ('setArmed(', 'startSelectedPact(', 'startService(', 'startForegroundService('):
            self.assertNotIn(old, installed)
        self.assertLess(start.index('if (gateway != null)'), start.index('appMode.setArmed(true)'))

    def test_start_callbacks_capture_the_exact_registered_route(self):
        self.assertIn('DeviceStartResultRegistry(getActivityResultRegistry(), deviceStartUi::granted)', self.home)
        self.assertIn('savedInstanceState.getBundle("device_start_result_routes"), deviceStart.pendingRoute()', self.home)
        self.assertIn('registerLive(route.registrationKey, entry)', self.registry)
        self.assertIn('route.returned(', self.registry)
        self.assertNotIn('permissionReturned(Permission', self.home)
        self.assertNotIn('permissionReturned(RuntimeStartAttempt.Permission', self.flow)
        self.assertIn('ViewModelProvider(this).get(DeviceStartViewModel.class)', self.home)
        self.assertIn('deviceStartResults.close()', body(self.home, 'protected void onDestroy()'))

    def test_legacy_continuations_refuse_after_installation(self):
        for signature in ('private void continueStartFlow()', 'private void requestProjection()',
                          'private void startSelectedPact()'):
            method = body(self.home, signature)
            gate = body(method, 'if (AppRuntimeGateway.current(this) != null)')
            self.assertIn('return;', gate)
        legacy = self.home[self.home.index('        projectionPermission ='):self.home.index('        overlayPermission =')]
        self.assertLess(legacy.index('AppRuntimeGateway.current(this)'), legacy.index('setArmed(true)'))

    def test_actual_callback_is_posted_and_never_faked_by_cancellation(self):
        callback = body(self.flow, 'private void permissionReturned(')
        self.assertLess(callback.index('post(() ->'), callback.index('owner.permissionResult('))
        self.assertIn('RuntimeStartAttempt.Prompt exact = delivery.prompt', callback)
        self.assertIn('Attempt owner = delivery.owner', callback)
        self.assertIn('submitted.compareAndSet(false, true)', self.flow)
        self.assertNotIn('.resultReturned()', self.flow)
        self.assertNotIn('getSharedPreferences', self.flow)
        self.assertNotIn('SavedStateHandle', self.flow)
        self.assertNotIn('Bundle', self.flow.split('public final class', 1)[1])
        cancellation = body(self.flow, 'private void abort(')
        self.assertIn('cancel(attempt)', cancellation)
        self.assertNotIn('permissionResult', cancellation)

    def test_mutation_permits_span_original_runtime_transactions(self):
        files = {
            'AppModeManager.java': ('public void setArmed(', 'public void save('),
            'ProtectionSessionManager.java': ('public static void markMediaProjectionStarted(',
                                              'public static void markMediaProjectionExplicitlyStopped('),
        }
        for name, signatures in files.items():
            source = (JAVA / 'appmode' / name).read_text()
            for signature in signatures:
                method = body(source, signature)
                self.assertLess(method.index('AppRuntimeGateway.admitDeviceMutation('),
                                method.index('synchronized (RuntimeSessionIdentity.LOCK)'))
                self.assertIn('finally { permit.close(); }', method)
                self.assertNotIn('catch (', method)
                self.assertIn('RuntimeSessionIdentity.changed()', method)

    def test_retired_routes_are_bounded_inert_tombstones(self):
        retire = body(self.registry, 'private void retire(')
        self.assertIn('entry.route = null; entry.observe = null', retire)
        self.assertIn('IGNORE_PERMISSION', retire)
        self.assertIn('IGNORE_ACTIVITY', retire)
        self.assertNotIn('.unregister()', retire)
        self.assertIn('deviceStartResults.onFrameworkResultReturned()', body(self.home, 'protected void onActivityResult('))
        self.assertIn('deviceStartResults.saveMetadata()', body(self.home, 'protected void onSaveInstanceState('))
        self.assertIn('entries.size() >= MAX_ROUTES', self.registry)
        self.assertIn('delivery = null', body(self.flow, 'private void retire()'))
        launch = body(self.flow, 'private void launchPending()')
        self.assertLess(launch.index('host.prepare(pending)'), launch.index('exact.prompt.launch('))

    def test_raw_result_refresh_keeps_exact_key_and_never_relaunches(self):
        refresh = body(self.registry, 'public void onFrameworkResultReturned()')
        self.assertIn('registerLive(item.getKey(), entry)', refresh)
        register = body(self.registry, 'private void registerLive(')
        self.assertIn('PromptRoute route = entry.route', register)
        self.assertIn('registry.register(key,', register)
        self.assertNotIn('.unregister()', refresh + register)
        self.assertNotIn('.launch(', refresh + register)
        for signature, call in (('protected void onActivityResult(', 'super.onActivityResult('),
                                ('public void onRequestPermissionsResult(', 'super.onRequestPermissionsResult(')):
            callback = body(self.home, signature)
            self.assertLess(callback.index(call), callback.index('onFrameworkResultReturned()'))

    def test_fresh_home_is_explicit_guarded_new_instance_not_recreation(self):
        guard = body(self.home, 'private boolean canOpenFreshStartHome()')
        for text in ('deviceStartUi.available()', '!deviceStart.busy()',
                     'deviceStart.pendingRoute() == null', 'deviceStartResults.needsFreshHome()'):
            self.assertIn(text, guard)
        action = body(self.home, 'private void openFreshStartHome()')
        self.assertIn('if (!canOpenFreshStartHome()) return', action)
        self.assertIn('new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)', action)
        self.assertLess(action.index('startActivity(fresh)'), action.index('finish()'))
        for text in ('addFlags(', '.recreate()', '.cancel()', '.clear()', '.restore(', 'setArmed('):
            self.assertNotIn(text, action)
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        main = manifest[manifest.index('android:name=".MainActivity"'):].split('/>', 1)[0]
        self.assertNotIn('launchMode', main)
        self.assertIn('entry.route != null', body(self.registry, 'public boolean needsFreshHome()'))
        self.assertNotIn('Recreate Home before', self.registry)

    def test_readiness_security_prompt_remains_separate_from_start(self):
        readiness = body(self.home, 'private void continuePermissionReadinessFlow()')
        self.assertIn('deviceAdminPermission.launch(new HardcoreModeManager(this).activationIntent())', readiness)
        self.assertNotIn('deviceStart.', readiness)
        self.assertNotIn('RuntimeStartAttempt.Permission.DEVICE_ADMIN', self.home)


if __name__ == '__main__':
    unittest.main()
