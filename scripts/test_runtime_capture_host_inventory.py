"""Source guards for the inactive host split, not OS/runtime/cleanup qualification."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'app/src/main/java/com/subhub/app/service'


def body(source, signature):
    start = source.index('{', source.index(signature))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start + 1:end - 1]


class RuntimeCaptureHostInventoryTest(unittest.TestCase):
    def setUp(self):
        self.projection = (SERVICE / 'ScreenCaptureService.java').read_text()
        self.accessibility = (SERVICE / 'ScreenshotAccessibilityService.java').read_text()
        self.host = (SERVICE / 'RuntimeCaptureHost.java').read_text()

    def test_service_field_initializers_do_not_acquire_shared_taps(self):
        for source in (self.projection, self.accessibility):
            self.assertNotRegex(source, r'private\s+(?:final\s+)?CensorTapTracker\s+tapTracker\s*=')
            self.assertEqual(source.count('CensorTapTracker.shared()'), 1)
            self.assertIn('CensorTapTracker.shared()', body(source, 'private void initializeDeviceRuntime()'))

    def test_projection_creation_is_os_only(self):
        creation = body(self.projection, 'public void onCreate()')
        self.assertNotIn('initializeDeviceRuntime', creation)
        for banned in ('SettingsRepository', 'StatsRepository', 'PenanceManager', 'Executors.', 'CensorTapTracker'):
            self.assertNotIn(banned, creation)

    def test_projection_actions_route_before_initialization(self):
        start = body(self.projection, 'public int onStartCommand(')
        initialization = start.index('initializeDeviceRuntime()')
        for boundary in ('intent == null', 'ACTION_STOP_OWNED', 'ACTION_STOP.equals', '!ACTION_START.equals'):
            self.assertLess(start.index(boundary), initialization)
        stop = start[start.index('if (ACTION_STOP.equals'):start.index('if (!ACTION_START.equals')]
        self.assertIn('if (!deviceInitialized)', stop)
        self.assertNotIn('initializeDeviceRuntime()', stop)

    def test_unselected_shell_cleanup_returns_before_device_cleanup(self):
        for source in (self.projection, self.accessibility):
            cleanup = body(source, 'public void onDestroy()')
            neutral = body(cleanup, 'if (!deviceInitialized)')
            self.assertIn('super.onDestroy()', neutral)
            self.assertIn('return;', neutral)
            for banned in ('stats', 'tapTracker', 'PopupStormManager', 'ForegroundAppState', 'AppModeManager'):
                self.assertNotIn(banned, neutral)

    def test_accessibility_api_guard_and_neutral_event_fence(self):
        connect = body(self.accessibility, 'protected void onServiceConnected()')
        self.assertLess(connect.index('Build.VERSION_CODES.R'), connect.index('initializeDeviceRuntime()'))
        self.assertNotIn('MainThreadSampler', connect)
        events = body(self.accessibility, 'public void onAccessibilityEvent(')
        self.assertLess(events.index('if (!deviceInitialized) return'), events.index('refreshCensorScreen'))

    def test_inactive_contract_is_not_an_installed_gateway(self):
        self.assertNotIn('RuntimeCaptureHost.', self.projection)
        self.assertNotIn('RuntimeCaptureHost.', self.accessibility)
        self.assertNotRegex(self.host, r'static\s+(?:volatile\s+)?Gateway\s+')
        self.assertNotIn('getSharedPreferences(', self.host)
        self.assertNotIn('CensorTapTracker.shared()', self.host)
        self.assertNotIn('getMediaProjection(', self.host)


if __name__ == '__main__':
    unittest.main()
