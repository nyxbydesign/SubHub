"""Drain Gradle output and report only bounded, fixed diagnostic labels.

Never retain a raw log or forward arbitrary output. This is a lossy diagnostic,
not a success oracle: the workflow separately preserves Gradle's exit status.
"""
import json
import re
import sys

CHUNK_BYTES = 4096
MAX_LINE_BYTES = 8192
MAX_CATEGORIES = 16
MAX_JAVAC_DIAGNOSTICS = 8
MAX_JAVAC_LINE = 999999
MAX_OUTPUT_BYTES = 4096
FAILURE_EXIT = 70

EXCEPTIONS = {
    b"org.gradle.api.tasks.TaskExecutionException": "task_execution",
    b"org.gradle.api.internal.tasks.compile.CompilationFailedException": "java_compilation",
    b"org.jetbrains.kotlin.gradle.tasks.CompilationErrorException": "kotlin_compilation",
    b"com.android.builder.internal.aapt.v2.Aapt2Exception": "android_resources",
    b"com.android.ide.common.resources.ResourceCompilationException": "resource_compilation",
    b"org.gradle.api.tasks.VerificationException": "verification",
    b"org.gradle.api.internal.exceptions.MarkedVerificationException": "verification",
    b"org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException": "worker_execution",
    b"org.gradle.internal.exceptions.DefaultMultiCauseException": "multiple_causes",
    b"org.gradle.execution.MultipleBuildFailures": "multiple_failures",
    b"org.gradle.api.GradleException": "gradle",
    b"org.gradle.api.internal.tasks.compile.JdkJavaCompiler$CompilationFailedException": "java_compilation",
    b"org.gradle.api.tasks.testing.TestExecutionException": "test_execution",
    b"org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration$ArtifactResolveException": "dependency_resolution",
    b"org.gradle.internal.resolve.ModuleVersionResolveException": "dependency_resolution",
    b"com.android.builder.packaging.PackagerException": "apk_packaging",
    b"com.android.ide.common.process.ProcessException": "process",
    b"java.lang.OutOfMemoryError": "out_of_memory",
    b"java.lang.NullPointerException": "null_pointer",
    b"java.lang.IllegalStateException": "illegal_state",
    b"java.lang.IllegalArgumentException": "illegal_argument",
    b"java.io.IOException": "io",
    b"java.io.FileNotFoundException": "missing_file",
    b"java.io.EOFException": "unexpected_eof",
    b"java.nio.file.NoSuchFileException": "missing_file",
    b"java.nio.file.AccessDeniedException": "access_denied",
    b"java.nio.file.FileSystemException": "filesystem",
    b"java.nio.channels.ClosedChannelException": "closed_channel",
    b"java.util.zip.ZipException": "zip",
}
RESOURCE_MESSAGES = {
    b"No space left on device": "no_space",
    b"Disk quota exceeded": "disk_quota",
    b"Too many open files": "file_limit",
    b"Permission denied": "permission_denied",
    b"Read-only file system": "read_only_filesystem",
}
RESOURCE_EXCEPTIONS = {
    b"java.io.IOException", b"java.io.FileNotFoundException",
    b"java.nio.file.FileSystemException", b"java.nio.file.AccessDeniedException",
}
TASKS = {
    # Fixed stage labels only; never forward source paths or compiler messages.
    b":app:compileDebugJavaWithJavac": "java_compile",
    b":app:compileDebugUnitTestJavaWithJavac": "unit_java_compile",
    b":app:compileDebugAndroidTestJavaWithJavac": "native_java_compile",
    b":app:checkDebugAarMetadata": "dependency_metadata",
    b":app:mergeDebugResources": "merge_resources",
    b":app:processDebugResources": "process_resources",
    b":app:processDebugMainManifest": "main_manifest",
    b":app:compileDebugKotlin": "kotlin_compile",
    b":app:compileDebugUnitTestKotlin": "unit_kotlin_compile",
    b":app:parseDebugLocalResources": "resource_parse",
    b":app:lintAnalyzeDebug": "lint_analysis",
    b":app:lintReportDebug": "lint_report",
    b":app:testDebugUnitTest": "unit_tests",
    b":app:lintDebug": "lint",
    b":app:assembleDebug": "assemble",
    b":app:packageDebug": "package",
}
STATUSES = {None: "seen", b"FAILED": "failed", b"UP-TO-DATE": "up_to_date",
            b"FROM-CACHE": "from_cache", b"SKIPPED": "skipped", b"NO-SOURCE": "no_source"}
# Fixed labels only. Never publish a lint message, path, line number, symbol or source excerpt.
LINT_ISSUES = {
    b"NewApi": "new_api", b"InlinedApi": "inlined_api",
    b"WrongConstant": "wrong_constant", b"RestrictedApi": "restricted_api",
    b"MissingPermission": "missing_permission", b"OverrideAbstract": "override_abstract",
    b"NotInstantiatable": "not_instantiatable", b"UnsafeOptInUsageError": "unsafe_opt_in",
}
LINT_SOURCES = {
    b"app/src/main/java/com/subhub/app/internal/InternalSubContext.java": "internal_editor_context",
    b"app/src/main/java/com/subhub/app/internal/InternalConfigurationStore.java": "internal_configuration",
    b"app/src/main/java/com/subhub/app/internal/InternalStorageScope.java": "internal_storage_scope",
    b"app/src/main/java/com/subhub/app/internal/FileInternalSubBackend.java": "internal_file_backend",
    b"app/src/main/java/com/subhub/app/internal/InternalSubStore.java": "internal_store",
    b"app/src/main/java/com/subhub/app/internal/InternalSubRecord.java": "internal_record",
    b"app/src/main/java/com/subhub/app/internal/InternalRecordCodec.java": "internal_record_codec",
    b"app/src/main/java/com/subhub/app/internal/InternalProfile.java": "internal_profile",
    b"app/src/main/java/com/subhub/app/internal/InternalProfileAdapter.java": "internal_profile_adapter",
    b"app/src/main/java/com/subhub/app/runtime/SQLiteRuntimeOwnerBackend.java": "runtime_owner_database",
    b"app/src/test/java/com/subhub/app/internal/InternalStorageChecks.java": "internal_storage_checks",
    b"app/src/test/java/com/subhub/app/internal/InternalConfigurationTest.java": "internal_configuration_tests",
    b"app/src/androidTest/java/com/subhub/app/internal/InternalStorageAndroidTest.java": "internal_storage_native_tests",
    b"app/src/androidTest/java/com/subhub/app/runtime/RuntimeOwnerDatabaseAndroidTest.java": "runtime_owner_native_tests",
}
# Source labels are reviewed literals, not filenames copied from compiler output.
# Cover every tracked app/src/test Java file, including pre-existing tests/helpers.
# New/renamed files require an explicit allowlist edit; unknown paths stay unknown.
JAVAC_SOURCES = {
    b"app/src/test/java/com/subhub/app/FoundationTest.java": "unit_foundation",
    b"app/src/test/java/com/subhub/app/StringCapitalizationTest.java": "unit_string_capitalization",
    b"app/src/test/java/com/subhub/app/activity/ActivityDomainTest.java": "unit_activity_domain",
    b"app/src/test/java/com/subhub/app/activity/ActivityEntryAppIconTest.java": "unit_activity_entry_app_icon",
    b"app/src/test/java/com/subhub/app/activity/ActivityFeedSnapshotTest.java": "unit_activity_feed_snapshot",
    b"app/src/test/java/com/subhub/app/activity/ActivityGroupTest.java": "unit_activity_group",
    b"app/src/test/java/com/subhub/app/activity/ActivitySourcesTest.java": "unit_activity_sources",
    b"app/src/test/java/com/subhub/app/appmode/AppModePolicyTest.java": "unit_app_mode_policy",
    b"app/src/test/java/com/subhub/app/appmode/AppTimeOrderTest.java": "unit_app_time_order",
    b"app/src/test/java/com/subhub/app/appmode/AppTimerManagerTest.java": "unit_app_timer_manager",
    b"app/src/test/java/com/subhub/app/appmode/AppTimerOwnerTest.java": "unit_app_timer_owner",
    b"app/src/test/java/com/subhub/app/appmode/AppTimerRuntimePolicyTest.java": "unit_app_timer_runtime_policy",
    b"app/src/test/java/com/subhub/app/appmode/ExtraTimeAllowanceTest.java": "unit_extra_time_allowance",
    b"app/src/test/java/com/subhub/app/appmode/ForegroundAppStateTest.java": "unit_foreground_app_state",
    b"app/src/test/java/com/subhub/app/appmode/LegacyAppSelectionTest.java": "unit_legacy_app_selection",
    b"app/src/test/java/com/subhub/app/appmode/LimitsUsageTest.java": "unit_limits_usage",
    b"app/src/test/java/com/subhub/app/capture/MediaProjectionLeaseRegistryTest.java": "unit_media_projection_lease_registry",
    b"app/src/test/java/com/subhub/app/capture/export/ExportOptionsTest.java": "unit_export_options",
    b"app/src/test/java/com/subhub/app/censor/android/CensorConfigurationKeyTest.java": "unit_censor_configuration_key",
    b"app/src/test/java/com/subhub/app/censor/android/FixtureDiagnosticQueryTest.java": "unit_fixture_diagnostic_query",
    b"app/src/test/java/com/subhub/app/censor/android/FixtureServiceConnectionTest.java": "unit_fixture_service_connection",
    b"app/src/test/java/com/subhub/app/censor/android/NativeReaderRetirementTest.java": "unit_native_reader_retirement",
    b"app/src/test/java/com/subhub/app/censor/android/PaintResourceKeyTest.java": "unit_paint_resource_key",
    b"app/src/test/java/com/subhub/app/censor/android/PresentationFrameTest.java": "unit_presentation_frame",
    b"app/src/test/java/com/subhub/app/censor/android/ScrollWitnessPixelsTest.java": "unit_scroll_witness_pixels",
    b"app/src/test/java/com/subhub/app/censor/android/SurfaceSubmissionTest.java": "unit_surface_submission",
    b"app/src/test/java/com/subhub/app/censor/android/WindowReadCostTest.java": "unit_window_read_cost",
    b"app/src/test/java/com/subhub/app/censor/engine/BoundedLatestWorkTest.java": "unit_bounded_latest_work",
    b"app/src/test/java/com/subhub/app/censor/engine/CensorCandidateGateTest.java": "unit_censor_candidate_gate",
    b"app/src/test/java/com/subhub/app/censor/engine/CensorEngineTest.java": "unit_censor_engine",
    b"app/src/test/java/com/subhub/app/censor/engine/ImagePresentationEstimateTest.java": "unit_image_presentation_estimate",
    b"app/src/test/java/com/subhub/app/censor/engine/LatestReconciliationTest.java": "unit_latest_reconciliation",
    b"app/src/test/java/com/subhub/app/censor/engine/LatestResourceCoordinatorTest.java": "unit_latest_resource_coordinator",
    b"app/src/test/java/com/subhub/app/censor/engine/MotionPairTest.java": "unit_motion_pair",
    b"app/src/test/java/com/subhub/app/censor/engine/NativeAccountingProvenanceTest.java": "unit_native_accounting_provenance",
    b"app/src/test/java/com/subhub/app/censor/engine/NativeReadCostTest.java": "unit_native_read_cost",
    b"app/src/test/java/com/subhub/app/censor/engine/NativeTextEngineTest.java": "unit_native_text_engine",
    b"app/src/test/java/com/subhub/app/censor/engine/NativeTextFrameTest.java": "unit_native_text_frame",
    b"app/src/test/java/com/subhub/app/censor/engine/PaintMappingTest.java": "unit_paint_mapping",
    b"app/src/test/java/com/subhub/app/censor/engine/PaintReceiptContractTest.java": "unit_paint_receipt_contract",
    b"app/src/test/java/com/subhub/app/censor/engine/PixelGeometryTest.java": "unit_pixel_geometry",
    b"app/src/test/java/com/subhub/app/censor/engine/PixelPairMatcherTest.java": "unit_pixel_pair_matcher",
    b"app/src/test/java/com/subhub/app/censor/engine/ProjectionCaptureAdapterTest.java": "unit_projection_capture_adapter",
    b"app/src/test/java/com/subhub/app/censor/engine/ProjectionFrameMailboxTest.java": "unit_projection_frame_mailbox",
    b"app/src/test/java/com/subhub/app/censor/engine/ProjectionFrameRetirementTest.java": "unit_projection_frame_retirement",
    b"app/src/test/java/com/subhub/app/censor/engine/ProjectionRetirementCompletionTest.java": "unit_projection_retirement_completion",
    b"app/src/test/java/com/subhub/app/censor/engine/ProjectionSourceSessionTest.java": "unit_projection_source_session",
    b"app/src/test/java/com/subhub/app/censor/engine/RenderSourceContractTest.java": "unit_render_source_contract",
    b"app/src/test/java/com/subhub/app/censor/engine/Rgba8888PlaneTest.java": "unit_rgba8888_plane",
    b"app/src/test/java/com/subhub/app/censor/engine/SceneEngineTest.java": "unit_scene_engine",
    b"app/src/test/java/com/subhub/app/censor/engine/SceneEvidenceTest.java": "unit_scene_evidence",
    b"app/src/test/java/com/subhub/app/censor/engine/ScrollMotionLedgerTest.java": "unit_scroll_motion_ledger",
    b"app/src/test/java/com/subhub/app/censor/engine/VisualInferencePolicyTest.java": "unit_visual_inference_policy",
    b"app/src/test/java/com/subhub/app/commitment/PactDurationTest.java": "unit_pact_duration",
    b"app/src/test/java/com/subhub/app/configuration/ConfigurationArtworkTest.java": "unit_configuration_artwork",
    b"app/src/test/java/com/subhub/app/configuration/ConfigurationSchemaTest.java": "unit_configuration_schema",
    b"app/src/test/java/com/subhub/app/configuration/ConfigurationSettingCatalogTest.java": "unit_configuration_setting_catalog",
    b"app/src/test/java/com/subhub/app/configuration/DraftPreferencesTest.java": "unit_draft_preferences",
    b"app/src/test/java/com/subhub/app/configuration/FeatureEditorSessionTest.java": "unit_feature_editor_session",
    b"app/src/test/java/com/subhub/app/configuration/InternalEditorSaveDrainTest.java": "unit_internal_editor_save_drain",
    b"app/src/test/java/com/subhub/app/detection/BBoxTest.java": "unit_b_box",
    b"app/src/test/java/com/subhub/app/detection/BoundedRgbInputTest.java": "unit_bounded_rgb_input",
    b"app/src/test/java/com/subhub/app/detection/CensorCoverageTest.java": "unit_censor_coverage",
    b"app/src/test/java/com/subhub/app/detection/CpuExecutionBudgetTest.java": "unit_cpu_execution_budget",
    b"app/src/test/java/com/subhub/app/detection/DetectionPostProcessorTest.java": "unit_detection_post_processor",
    b"app/src/test/java/com/subhub/app/detection/DetectionPresetTest.java": "unit_detection_preset",
    b"app/src/test/java/com/subhub/app/detection/FastVisualGateTest.java": "unit_fast_visual_gate",
    b"app/src/test/java/com/subhub/app/detection/InfractionTrackerTest.java": "unit_infraction_tracker",
    b"app/src/test/java/com/subhub/app/detection/NudeNetClassCatalogTest.java": "unit_nude_net_class_catalog",
    b"app/src/test/java/com/subhub/app/detection/ObjectTrackerTest.java": "unit_object_tracker",
    b"app/src/test/java/com/subhub/app/detection/PipelineIdlePolicyTest.java": "unit_pipeline_idle_policy",
    b"app/src/test/java/com/subhub/app/detection/RectangularDetectionPostProcessorTest.java": "unit_rectangular_detection_post_processor",
    b"app/src/test/java/com/subhub/app/detection/RenderSourceReferenceTest.java": "unit_render_source_reference",
    b"app/src/test/java/com/subhub/app/detection/ResourceCleanupFailureTest.java": "unit_resource_cleanup_failure",
    b"app/src/test/java/com/subhub/app/detection/TensorInspectionTest.java": "unit_tensor_inspection",
    b"app/src/test/java/com/subhub/app/detection/VisualDetectionStabilizerTest.java": "unit_visual_detection_stabilizer",
    b"app/src/test/java/com/subhub/app/detection/VisualIdentityReconcilerTest.java": "unit_visual_identity_reconciler",
    b"app/src/test/java/com/subhub/app/detection/VisualTrackArbitratorTest.java": "unit_visual_track_arbitrator",
    b"app/src/test/java/com/subhub/app/detection/VisualTrackCategorySafetyTest.java": "unit_visual_track_category_safety",
    b"app/src/test/java/com/subhub/app/detection/WholePersonGeometryTest.java": "unit_whole_person_geometry",
    b"app/src/test/java/com/subhub/app/detection/text/DetectionFusionTest.java": "unit_detection_fusion",
    b"app/src/test/java/com/subhub/app/detection/text/ImageTextLayoutTest.java": "unit_image_text_layout",
    b"app/src/test/java/com/subhub/app/detection/text/NativeAccountingScheduleTest.java": "unit_native_accounting_schedule",
    b"app/src/test/java/com/subhub/app/detection/text/NativeNodeIdentityCacheTest.java": "unit_native_node_identity_cache",
    b"app/src/test/java/com/subhub/app/detection/text/NativeReadScheduleTest.java": "unit_native_read_schedule",
    b"app/src/test/java/com/subhub/app/detection/text/NativeTextContentKeyTest.java": "unit_native_text_content_key",
    b"app/src/test/java/com/subhub/app/detection/text/OcrCallbackDispatcherTest.java": "unit_ocr_callback_dispatcher",
    b"app/src/test/java/com/subhub/app/detection/text/OcrRecognizerLeaseTest.java": "unit_ocr_recognizer_lease",
    b"app/src/test/java/com/subhub/app/detection/text/SmutTextClassifierTest.java": "unit_smut_text_classifier",
    b"app/src/test/java/com/subhub/app/detection/text/TextDetectionCoordinateMapperTest.java": "unit_text_detection_coordinate_mapper",
    b"app/src/test/java/com/subhub/app/detection/text/TextDetectionStabilizerTest.java": "unit_text_detection_stabilizer",
    b"app/src/test/java/com/subhub/app/detection/text/TextOffsetMappingTest.java": "unit_text_offset_mapping",
    b"app/src/test/java/com/subhub/app/detection/text/TextRegionProjectorTest.java": "unit_text_region_projector",
    b"app/src/test/java/com/subhub/app/diagnostics/CensorLabCensorSessionTest.java": "unit_censor_lab_censor_session",
    b"app/src/test/java/com/subhub/app/diagnostics/CensorLabEventBufferTest.java": "unit_censor_lab_event_buffer",
    b"app/src/test/java/com/subhub/app/diagnostics/CensorLabLogTest.java": "unit_censor_lab_log",
    b"app/src/test/java/com/subhub/app/diagnostics/CensorLabVideoProfileTest.java": "unit_censor_lab_video_profile",
    b"app/src/test/java/com/subhub/app/diagnostics/ReplacementEngineTelemetryTest.java": "unit_replacement_engine_telemetry",
    b"app/src/test/java/com/subhub/app/internal/InternalConfigurationTest.java": "unit_internal_configuration",
    b"app/src/test/java/com/subhub/app/internal/InternalRuntimePreferencesTest.java": "unit_internal_runtime_preferences",
    b"app/src/test/java/com/subhub/app/internal/InternalStorageChecks.java": "unit_internal_storage_checks",
    b"app/src/test/java/com/subhub/app/internal/InternalStorageTest.java": "unit_internal_storage",
    b"app/src/test/java/com/subhub/app/internal/InternalTaskBoundaryTest.java": "unit_internal_task_boundary",
    b"app/src/test/java/com/subhub/app/internal/OwnerDataProvisioningTest.java": "unit_owner_data_provisioning",
    b"app/src/test/java/com/subhub/app/journal/JournalEntryTest.java": "unit_journal_entry",
    b"app/src/test/java/com/subhub/app/onboarding/BothOnboardingFlowTest.java": "unit_both_onboarding_flow",
    b"app/src/test/java/com/subhub/app/onboarding/DomOnboardingFlowTest.java": "unit_dom_onboarding_flow",
    b"app/src/test/java/com/subhub/app/onboarding/OnboardingFlowTest.java": "unit_onboarding_flow",
    b"app/src/test/java/com/subhub/app/onboarding/SubOnboardingFlowTest.java": "unit_sub_onboarding_flow",
    b"app/src/test/java/com/subhub/app/overlay/AndroidSplineScrollTest.java": "unit_android_spline_scroll",
    b"app/src/test/java/com/subhub/app/overlay/BodyOverlapHeuristicsTest.java": "unit_body_overlap_heuristics",
    b"app/src/test/java/com/subhub/app/overlay/CensorLabelLayoutTest.java": "unit_censor_label_layout",
    b"app/src/test/java/com/subhub/app/overlay/CensorPhrasesTest.java": "unit_censor_phrases",
    b"app/src/test/java/com/subhub/app/overlay/CensorPipelineReplayTest.java": "unit_censor_pipeline_replay",
    b"app/src/test/java/com/subhub/app/overlay/ContentSpaceCoordinatesTest.java": "unit_content_space_coordinates",
    b"app/src/test/java/com/subhub/app/overlay/ContinuousTrackSteeringTest.java": "unit_continuous_track_steering",
    b"app/src/test/java/com/subhub/app/overlay/IntegerProviderScrollBrakeTest.java": "unit_integer_provider_scroll_brake",
    b"app/src/test/java/com/subhub/app/overlay/MeasurementReconciliationTest.java": "unit_measurement_reconciliation",
    b"app/src/test/java/com/subhub/app/overlay/NativeScrollTrajectoryTest.java": "unit_native_scroll_trajectory",
    b"app/src/test/java/com/subhub/app/overlay/PersonCoveragePresentationTest.java": "unit_person_coverage_presentation",
    b"app/src/test/java/com/subhub/app/overlay/PersonDecorationLayoutTest.java": "unit_person_decoration_layout",
    b"app/src/test/java/com/subhub/app/overlay/RenderCoordinatesTest.java": "unit_render_coordinates",
    b"app/src/test/java/com/subhub/app/overlay/RenderLayoutDiagnosticsTest.java": "unit_render_layout_diagnostics",
    b"app/src/test/java/com/subhub/app/overlay/RenderReferenceProjectionTest.java": "unit_render_reference_projection",
    b"app/src/test/java/com/subhub/app/overlay/RenderTrackSnapshotTest.java": "unit_render_track_snapshot",
    b"app/src/test/java/com/subhub/app/overlay/ScrollDeliveryAgeTest.java": "unit_scroll_delivery_age",
    b"app/src/test/java/com/subhub/app/overlay/ScrollMotionDiagnosticsTest.java": "unit_scroll_motion_diagnostics",
    b"app/src/test/java/com/subhub/app/overlay/ScrollSlowdownRegressionTest.java": "unit_scroll_slowdown_regression",
    b"app/src/test/java/com/subhub/app/overlay/ScrollTrajectorySmoothnessTest.java": "unit_scroll_trajectory_smoothness",
    b"app/src/test/java/com/subhub/app/overlay/ShortGapCorrectionContractTest.java": "unit_short_gap_correction_contract",
    b"app/src/test/java/com/subhub/app/overlay/ShortGapSlowdownTest.java": "unit_short_gap_slowdown",
    b"app/src/test/java/com/subhub/app/overlay/StableVisualLayoutTest.java": "unit_stable_visual_layout",
    b"app/src/test/java/com/subhub/app/overlay/ViewportMotionTest.java": "unit_viewport_motion",
    b"app/src/test/java/com/subhub/app/overlay/VisualRenderRegionConsolidatorTest.java": "unit_visual_render_region_consolidator",
    b"app/src/test/java/com/subhub/app/overlay/ZeroResidualBrakeContractTest.java": "unit_zero_residual_brake_contract",
    b"app/src/test/java/com/subhub/app/overlay/ZeroResidualScrollTest.java": "unit_zero_residual_scroll",
    b"app/src/test/java/com/subhub/app/penance/AutoCashoutPolicyTest.java": "unit_auto_cashout_policy",
    b"app/src/test/java/com/subhub/app/penance/CensorTapTrackerTest.java": "unit_censor_tap_tracker",
    b"app/src/test/java/com/subhub/app/penance/InternalPenanceRulesTest.java": "unit_internal_penance_rules",
    b"app/src/test/java/com/subhub/app/penance/PayPalRequestPolicyTest.java": "unit_pay_pal_request_policy",
    b"app/src/test/java/com/subhub/app/penance/PayPalVaultPolicyTest.java": "unit_pay_pal_vault_policy",
    b"app/src/test/java/com/subhub/app/penance/PenancePolicyTest.java": "unit_penance_policy",
    b"app/src/test/java/com/subhub/app/penance/ViewportDwellEpisodeTest.java": "unit_viewport_dwell_episode",
    b"app/src/test/java/com/subhub/app/penance/WalletCurrencyTest.java": "unit_wallet_currency",
    b"app/src/test/java/com/subhub/app/penance/WalletHistoryGroupsTest.java": "unit_wallet_history_groups",
    b"app/src/test/java/com/subhub/app/people/ConfigurationAuthorityTest.java": "unit_configuration_authority",
    b"app/src/test/java/com/subhub/app/people/HomeOrderingTest.java": "unit_home_ordering",
    b"app/src/test/java/com/subhub/app/people/InternalVisibilityBarrierTest.java": "unit_internal_visibility_barrier",
    b"app/src/test/java/com/subhub/app/people/LastSeenFormatTest.java": "unit_last_seen_format",
    b"app/src/test/java/com/subhub/app/people/PersonDirectoryTest.java": "unit_person_directory",
    b"app/src/test/java/com/subhub/app/people/PersonDisplayOrderTest.java": "unit_person_display_order",
    b"app/src/test/java/com/subhub/app/people/PersonPresentationTest.java": "unit_person_presentation",
    b"app/src/test/java/com/subhub/app/people/PersonSnapshotAppIconTest.java": "unit_person_snapshot_app_icon",
    b"app/src/test/java/com/subhub/app/people/ProfileDataTest.java": "unit_profile_data",
    b"app/src/test/java/com/subhub/app/people/ProfileNamesTest.java": "unit_profile_names",
    b"app/src/test/java/com/subhub/app/permissions/HomePermissionPolicyTest.java": "unit_home_permission_policy",
    b"app/src/test/java/com/subhub/app/popup/PopupArtworkManifestTest.java": "unit_popup_artwork_manifest",
    b"app/src/test/java/com/subhub/app/popup/PopupRunOwnershipTest.java": "unit_popup_run_ownership",
    b"app/src/test/java/com/subhub/app/popup/PopupStormActivationPolicyTest.java": "unit_popup_storm_activation_policy",
    b"app/src/test/java/com/subhub/app/popup/PopupWorkScopeTest.java": "unit_popup_work_scope",
    b"app/src/test/java/com/subhub/app/popup/ScopedDetectionHintsTest.java": "unit_scoped_detection_hints",
    b"app/src/test/java/com/subhub/app/relationships/RelationshipPreparationKeyTest.java": "unit_relationship_preparation_key",
    b"app/src/test/java/com/subhub/app/relationships/RelationshipPresentationTest.java": "unit_relationship_presentation",
    b"app/src/test/java/com/subhub/app/relationships/RelationshipRecoveryUiTest.java": "unit_relationship_recovery_ui",
    b"app/src/test/java/com/subhub/app/relationships/RelationshipUiTest.java": "unit_relationship_ui",
    b"app/src/test/java/com/subhub/app/remote/DeviceDataSyncWakeTest.java": "unit_device_data_sync_wake",
    b"app/src/test/java/com/subhub/app/remote/ExtraTimeRequestPolicyTest.java": "unit_extra_time_request_policy",
    b"app/src/test/java/com/subhub/app/remote/FormalRelationshipContractTest.java": "unit_formal_relationship_contract",
    b"app/src/test/java/com/subhub/app/remote/LinkingStructuralReservationTest.java": "unit_linking_structural_reservation",
    b"app/src/test/java/com/subhub/app/remote/PairingLifecycleClockTest.java": "unit_pairing_lifecycle_clock",
    b"app/src/test/java/com/subhub/app/remote/PeerPresenceTest.java": "unit_peer_presence",
    b"app/src/test/java/com/subhub/app/remote/RemoteActivityWireTest.java": "unit_remote_activity_wire",
    b"app/src/test/java/com/subhub/app/remote/RemoteAppliedNotificationsTest.java": "unit_remote_applied_notifications",
    b"app/src/test/java/com/subhub/app/remote/RemoteAssetsTest.java": "unit_remote_assets",
    b"app/src/test/java/com/subhub/app/remote/RemoteConfigurationCompatibilityTest.java": "unit_remote_configuration_compatibility",
    b"app/src/test/java/com/subhub/app/remote/RemoteConfigurationEditsTest.java": "unit_remote_configuration_edits",
    b"app/src/test/java/com/subhub/app/remote/RemotePersonPresenceTest.java": "unit_remote_person_presence",
    b"app/src/test/java/com/subhub/app/remote/RemoteProfileDetailsTest.java": "unit_remote_profile_details",
    b"app/src/test/java/com/subhub/app/remote/RemoteProfilesTest.java": "unit_remote_profiles",
    b"app/src/test/java/com/subhub/app/remote/RemoteProtocolTest.java": "unit_remote_protocol",
    b"app/src/test/java/com/subhub/app/remote/RemoteSectionTransferSizingTest.java": "unit_remote_section_transfer_sizing",
    b"app/src/test/java/com/subhub/app/remote/RemoteSectionTransferTest.java": "unit_remote_section_transfer",
    b"app/src/test/java/com/subhub/app/remote/RemoteSessionCommandsTest.java": "unit_remote_session_commands",
    b"app/src/test/java/com/subhub/app/remote/RemoteSessionControlStateTest.java": "unit_remote_session_control_state",
    b"app/src/test/java/com/subhub/app/remote/RemoteSessionFailureSummaryTest.java": "unit_remote_session_failure_summary",
    b"app/src/test/java/com/subhub/app/remote/RemoteSyncCadenceTest.java": "unit_remote_sync_cadence",
    b"app/src/test/java/com/subhub/app/remote/RemoteSyncContentTest.java": "unit_remote_sync_content",
    b"app/src/test/java/com/subhub/app/remote/StableConnectionWakeTest.java": "unit_stable_connection_wake",
    b"app/src/test/java/com/subhub/app/remote/icons/AppIconCacheTest.java": "unit_app_icon_cache",
    b"app/src/test/java/com/subhub/app/remote/icons/AppIconExchangeTest.java": "unit_app_icon_exchange",
    b"app/src/test/java/com/subhub/app/remote/icons/IconFixtures.java": "unit_icon_fixtures",
    b"app/src/test/java/com/subhub/app/runtime/OwnerWriteQueueTest.java": "unit_owner_write_queue",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeConfigurationDispatchTest.java": "unit_runtime_configuration_dispatch",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeExecutionTest.java": "unit_runtime_execution",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeFoundationChecks.java": "unit_runtime_foundation_checks",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeFoundationTest.java": "unit_runtime_foundation",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeOwnerDirectorySyncTest.java": "unit_runtime_owner_directory_sync",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeOwnerRecordCodecChecks.java": "unit_runtime_owner_record_codec_checks",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeOwnerRecordCodecTest.java": "unit_runtime_owner_record_codec",
    b"app/src/test/java/com/subhub/app/runtime/RuntimePermitConcurrencyTest.java": "unit_runtime_permit_concurrency",
    b"app/src/test/java/com/subhub/app/runtime/SerialOwnerDataBrokerTest.java": "unit_serial_owner_data_broker",
    b"app/src/test/java/com/subhub/app/runtime/WhisperObservationTest.java": "unit_whisper_observation",
    b"app/src/test/java/com/subhub/app/security/HardcoreSettingsGuardTest.java": "unit_hardcore_settings_guard",
    b"app/src/test/java/com/subhub/app/security/ProtectionStopPolicyTest.java": "unit_protection_stop_policy",
    b"app/src/test/java/com/subhub/app/security/TotpTest.java": "unit_totp",
    b"app/src/test/java/com/subhub/app/service/AccessibilityCaptureSchedulerTest.java": "unit_accessibility_capture_scheduler",
    b"app/src/test/java/com/subhub/app/service/AccessibilitySurfaceIdentityResolverTest.java": "unit_accessibility_surface_identity_resolver",
    b"app/src/test/java/com/subhub/app/service/AsyncViewportAnchorSamplerTest.java": "unit_async_viewport_anchor_sampler",
    b"app/src/test/java/com/subhub/app/service/CaptureEpochTest.java": "unit_capture_epoch",
    b"app/src/test/java/com/subhub/app/service/CaptureGapProbeTest.java": "unit_capture_gap_probe",
    b"app/src/test/java/com/subhub/app/runtime/InternalSessionClockTest.java": "unit_internal_session_clock",
    b"app/src/test/java/com/subhub/app/internal/InternalSessionClockPersistenceTest.java": "unit_internal_session_clock_persistence",
    b"app/src/test/java/com/subhub/app/service/CaptureLoadGovernorOwnershipTest.java": "unit_capture_load_governor_ownership",
    b"app/src/test/java/com/subhub/app/service/CaptureLoadGovernorTest.java": "unit_capture_load_governor",
    b"app/src/test/java/com/subhub/app/service/CaptureRunOwnershipTest.java": "unit_capture_run_ownership",
    b"app/src/test/java/com/subhub/app/service/CaptureScrollTimelineTest.java": "unit_capture_scroll_timeline",
    b"app/src/test/java/com/subhub/app/service/CaptureTimeReferenceTest.java": "unit_capture_time_reference",
    b"app/src/test/java/com/subhub/app/service/ChromeGeometryProbeTest.java": "unit_chrome_geometry_probe",
    b"app/src/test/java/com/subhub/app/service/ContentSpaceRegionCacheTest.java": "unit_content_space_region_cache",
    b"app/src/test/java/com/subhub/app/service/CurrentOnlyQualityPresentationGateTest.java": "unit_current_only_quality_presentation_gate",
    b"app/src/test/java/com/subhub/app/service/FaceGeometryTraceTest.java": "unit_face_geometry_trace",
    b"app/src/test/java/com/subhub/app/service/FastPriorityInferenceGateTest.java": "unit_fast_priority_inference_gate",
    b"app/src/test/java/com/subhub/app/service/ForegroundLimitHandoffTest.java": "unit_foreground_limit_handoff",
    b"app/src/test/java/com/subhub/app/service/ForegroundUsageSessionTest.java": "unit_foreground_usage_session",
    b"app/src/test/java/com/subhub/app/service/ForegroundUsageWindowResolverTest.java": "unit_foreground_usage_window_resolver",
    b"app/src/test/java/com/subhub/app/service/ForegroundWindowResolverTest.java": "unit_foreground_window_resolver",
    b"app/src/test/java/com/subhub/app/service/GpuPreparationHealthTest.java": "unit_gpu_preparation_health",
    b"app/src/test/java/com/subhub/app/service/ImmediateQualityPresentationGateTest.java": "unit_immediate_quality_presentation_gate",
    b"app/src/test/java/com/subhub/app/service/InferenceBitmapPreparerTest.java": "unit_inference_bitmap_preparer",
    b"app/src/test/java/com/subhub/app/service/InferenceScrollReprojectorTest.java": "unit_inference_scroll_reprojector",
    b"app/src/test/java/com/subhub/app/service/LateQualityPresentationGateTest.java": "unit_late_quality_presentation_gate",
    b"app/src/test/java/com/subhub/app/service/LatestFrameBrokerTest.java": "unit_latest_frame_broker",
    b"app/src/test/java/com/subhub/app/service/NativeScrollCurvePolicyTest.java": "unit_native_scroll_curve_policy",
    b"app/src/test/java/com/subhub/app/service/PixelPairControlTest.java": "unit_pixel_pair_control",
    b"app/src/test/java/com/subhub/app/service/ProvisionalScrollSurfaceTest.java": "unit_provisional_scroll_surface",
    b"app/src/test/java/com/subhub/app/service/QualityBackfillCoordinatorTest.java": "unit_quality_backfill_coordinator",
    b"app/src/test/java/com/subhub/app/service/QualityBackfillCurrentOnlyTest.java": "unit_quality_backfill_current_only",
    b"app/src/test/java/com/subhub/app/service/QualityBackfillRunnerTest.java": "unit_quality_backfill_runner",
    b"app/src/test/java/com/subhub/app/service/QualityCacheEvidenceTest.java": "unit_quality_cache_evidence",
    b"app/src/test/java/com/subhub/app/service/QualityConcurrencyGovernorTest.java": "unit_quality_concurrency_governor",
    b"app/src/test/java/com/subhub/app/service/QualityPresentationAlignerTest.java": "unit_quality_presentation_aligner",
    b"app/src/test/java/com/subhub/app/service/QualityPresentationSlotTest.java": "unit_quality_presentation_slot",
    b"app/src/test/java/com/subhub/app/service/QualityTilePlannerTest.java": "unit_quality_tile_planner",
    b"app/src/test/java/com/subhub/app/service/RenderSourceTimelineTest.java": "unit_render_source_timeline",
    b"app/src/test/java/com/subhub/app/service/ReplacementOwnerCurveSelectionTest.java": "unit_replacement_owner_curve_selection",
    b"app/src/test/java/com/subhub/app/service/ReplacementScrollPresentationTest.java": "unit_replacement_scroll_presentation",
    b"app/src/test/java/com/subhub/app/service/RowMotionEstimatorTest.java": "unit_row_motion_estimator",
    b"app/src/test/java/com/subhub/app/service/RowMotionObserverTest.java": "unit_row_motion_observer",
    b"app/src/test/java/com/subhub/app/service/SceneTransactionCoordinatorTest.java": "unit_scene_transaction_coordinator",
    b"app/src/test/java/com/subhub/app/service/ScrollCompanionDeduplicatorTest.java": "unit_scroll_companion_deduplicator",
    b"app/src/test/java/com/subhub/app/service/ScrollDeltaStabilizerTest.java": "unit_scroll_delta_stabilizer",
    b"app/src/test/java/com/subhub/app/service/ScrollFrameMotionEstimatorTest.java": "unit_scroll_frame_motion_estimator",
    b"app/src/test/java/com/subhub/app/service/ScrollLookupScopeTest.java": "unit_scroll_lookup_scope",
    b"app/src/test/java/com/subhub/app/service/ScrollMetadataTraceTest.java": "unit_scroll_metadata_trace",
    b"app/src/test/java/com/subhub/app/service/ScrollSurfaceHysteresisTest.java": "unit_scroll_surface_hysteresis",
    b"app/src/test/java/com/subhub/app/service/SerialOwnedLookupTest.java": "unit_serial_owned_lookup",
    b"app/src/test/java/com/subhub/app/service/SourcePatchMatcherTest.java": "unit_source_patch_matcher",
    b"app/src/test/java/com/subhub/app/service/SourceTrackContinuityTest.java": "unit_source_track_continuity",
    b"app/src/test/java/com/subhub/app/service/SourceTrackLocalContinuityTest.java": "unit_source_track_local_continuity",
    b"app/src/test/java/com/subhub/app/service/SpatialFrameMapTest.java": "unit_spatial_frame_map",
    b"app/src/test/java/com/subhub/app/service/SpatialFrameRegistrationTest.java": "unit_spatial_frame_registration",
    b"app/src/test/java/com/subhub/app/service/SpatialRegionCacheTest.java": "unit_spatial_region_cache",
    b"app/src/test/java/com/subhub/app/service/TrackerPhaseProjectionTest.java": "unit_tracker_phase_projection",
    b"app/src/test/java/com/subhub/app/service/ViewportAnchorGeometryTest.java": "unit_viewport_anchor_geometry",
    b"app/src/test/java/com/subhub/app/service/VisualCameraShadowTest.java": "unit_visual_camera_shadow",
    b"app/src/test/java/com/subhub/app/service/VisualScrollReconcilerTest.java": "unit_visual_scroll_reconciler",
    b"app/src/test/java/com/subhub/app/settings/CensorAppearanceTest.java": "unit_censor_appearance",
    b"app/src/test/java/com/subhub/app/settings/CensorLookTest.java": "unit_censor_look",
    b"app/src/test/java/com/subhub/app/settings/DetectionCategorySelectionTest.java": "unit_detection_category_selection",
    b"app/src/test/java/com/subhub/app/stats/AchievementRequirementTest.java": "unit_achievement_requirement",
    b"app/src/test/java/com/subhub/app/stats/ActivityDateHistoryTest.java": "unit_activity_date_history",
    b"app/src/test/java/com/subhub/app/stats/DailyTimeSlicesTest.java": "unit_daily_time_slices",
    b"app/src/test/java/com/subhub/app/stats/DomAchievementCatalogTest.java": "unit_dom_achievement_catalog",
    b"app/src/test/java/com/subhub/app/stats/DomDailySnapshotTest.java": "unit_dom_daily_snapshot",
    b"app/src/test/java/com/subhub/app/stats/DomStatsSnapshotTest.java": "unit_dom_stats_snapshot",
    b"app/src/test/java/com/subhub/app/stats/DurationCheckpointDispatcherTest.java": "unit_duration_checkpoint_dispatcher",
    b"app/src/test/java/com/subhub/app/stats/OwnerRetainedHistoryTest.java": "unit_owner_retained_history",
    b"app/src/test/java/com/subhub/app/stats/RetainedStatisticsTest.java": "unit_retained_statistics",
    b"app/src/test/java/com/subhub/app/stats/SessionDurationProjectionTest.java": "unit_session_duration_projection",
    b"app/src/test/java/com/subhub/app/stats/SessionHistoryCompatibilityTest.java": "unit_session_history_compatibility",
    b"app/src/test/java/com/subhub/app/stats/SessionStatisticsCopyTest.java": "unit_session_statistics_copy",
    b"app/src/test/java/com/subhub/app/stats/SessionStreakTest.java": "unit_session_streak",
    b"app/src/test/java/com/subhub/app/stats/StatsSnapshotTest.java": "unit_stats_snapshot",
    b"app/src/test/java/com/subhub/app/storage/DisposableCacheTest.java": "unit_disposable_cache",
    b"app/src/test/java/com/subhub/app/storage/InternalAssetDurabilityTest.java": "unit_internal_asset_durability",
    b"app/src/test/java/com/subhub/app/storage/PayloadCodecTest.java": "unit_payload_codec",
    b"app/src/test/java/com/subhub/app/subliminal/SubliminalPhraseSelectionTest.java": "unit_subliminal_phrase_selection",
    b"app/src/test/java/com/subhub/app/subliminal/SubliminalSettingsRepositoryTest.java": "unit_subliminal_settings_repository",
    b"app/src/test/java/com/subhub/app/subliminal/SubliminalSettingsTest.java": "unit_subliminal_settings",
    b"app/src/test/java/com/subhub/app/subliminal/WhisperLifecycleTest.java": "unit_whisper_lifecycle",
    b"app/src/test/java/com/subhub/app/tasks/InternalTaskSchedulerTest.java": "unit_internal_task_scheduler",
    b"app/src/test/java/com/subhub/app/tasks/TaskContractFieldsTest.java": "unit_task_contract_fields",
    b"app/src/test/java/com/subhub/app/tasks/TaskDefinitionTest.java": "unit_task_definition",
    b"app/src/test/java/com/subhub/app/tasks/TaskEngineTest.java": "unit_task_engine",
    b"app/src/test/java/com/subhub/app/tasks/TaskFailurePolicyTest.java": "unit_task_failure_policy",
    b"app/src/test/java/com/subhub/app/tasks/TaskForegroundTickerTest.java": "unit_task_foreground_ticker",
    b"app/src/test/java/com/subhub/app/tasks/TaskJsonTest.java": "unit_task_json",
    b"app/src/test/java/com/subhub/app/tasks/TaskMediaTest.java": "unit_task_media",
    b"app/src/test/java/com/subhub/app/tasks/TaskNoticeLedgerTest.java": "unit_task_notice_ledger",
    b"app/src/test/java/com/subhub/app/tasks/TaskTimezoneTest.java": "unit_task_timezone",
    b"app/src/test/java/com/subhub/app/update/GitHubHttpTransportTest.java": "unit_git_hub_http_transport",
    b"app/src/test/java/com/subhub/app/update/GitHubReleaseRepositoryTest.java": "unit_git_hub_release_repository",
    b"app/src/test/java/com/subhub/app/update/ReleaseHistoryCatalogTest.java": "unit_release_history_catalog",
    b"app/src/test/java/com/subhub/app/update/ReleaseNotesFormatterTest.java": "unit_release_notes_formatter",
    b"app/src/test/java/com/subhub/app/update/ReleaseTimestampFormatterTest.java": "unit_release_timestamp_formatter",
    b"app/src/test/java/com/subhub/app/update/SemanticVersionTest.java": "unit_semantic_version",
    b"app/src/test/java/com/subhub/app/update/UpdateManifestTest.java": "unit_update_manifest",
    b"app/src/test/java/com/subhub/app/util/BoundedCopyTest.java": "unit_bounded_copy",
    b"app/src/test/java/com/subhub/app/util/ProgressMeterThemeTest.java": "unit_progress_meter_theme",
    b"app/src/test/java/com/subhub/app/util/RemoteAppIconTest.java": "unit_remote_app_icon",
    b"app/src/test/java/com/subhub/app/censor/android/CensorAccountingQueueTest.java": "unit_censor_accounting_queue",
    b"app/src/test/java/com/subhub/app/censor/android/CensorBindingWorkTest.java": "unit_censor_binding_work",
    b"app/src/test/java/com/subhub/app/censor/android/CensorReconciliationTest.java": "unit_censor_reconciliation",
    b"app/src/test/java/com/subhub/app/censor/android/DeferredCensorDemandTest.java": "unit_deferred_censor_demand",
    b"app/src/test/java/com/subhub/app/censor/android/OwnedCensorArtworkSelectionTest.java": "unit_owned_censor_artwork_selection",
    b"app/src/test/java/com/subhub/app/detection/OwnerDetectorProviderCacheTest.java": "unit_owner_detector_provider_cache",
    b"app/src/test/java/com/subhub/app/runtime/DeviceContinuationScopeTest.java": "unit_device_continuation_scope",
    b"app/src/test/java/com/subhub/app/runtime/RuntimePermissionAdmissionTest.java": "unit_runtime_permission_admission",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeStartAttemptTest.java": "unit_runtime_start_attempt",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeStartGatewayTest.java": "unit_runtime_start_gateway",
    b"app/src/test/java/com/subhub/app/service/OwnedCaptureLifetimeTest.java": "unit_owned_capture_lifetime",
    b"app/src/test/java/com/subhub/app/service/OwnedLegacyAccessibilityBindingTest.java": "unit_owned_legacy_accessibility_binding",
    b"app/src/test/java/com/subhub/app/service/OwnedProjectionBindingTest.java": "unit_owned_projection_binding",
    b"app/src/test/java/com/subhub/app/service/RuntimeCaptureHostTest.java": "unit_runtime_capture_host",
    b"app/src/test/java/com/subhub/app/capture/CustomImagePoolLifetimeTest.java": "unit_custom_image_pool_lifetime",
    b"app/src/test/java/com/subhub/app/overlay/LegacyRenderCallbacksTest.java": "unit_legacy_render_callbacks",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeJournalMigrationTest.java": "unit_runtime_journal_migration",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeSourceRevocationTest.java": "unit_runtime_source_revocation",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeInstallationBarrierTest.java": "unit_runtime_installation_barrier",
    b"app/src/test/java/com/subhub/app/runtime/DeviceRuntimeEffectsTest.java": "unit_device_runtime_effects",
    b"app/src/test/java/com/subhub/app/runtime/RuntimeFenceObservationTest.java": "unit_runtime_fence_observation",
    b"app/src/test/java/com/subhub/app/people/InternalPresentationStampTest.java": "unit_internal_presentation_stamp",
    b"app/src/test/java/com/subhub/app/stats/DailyStatsObservationGateTest.java": "unit_daily_stats_observation_gate",
    b"app/src/test/java/com/subhub/app/tasks/InternalTaskEffectDrainTest.java": "unit_internal_task_effect_drain",
    b"app/src/test/java/com/subhub/app/tasks/InternalTaskWorkOrderTest.java": "unit_internal_task_work_order",
    b"app/src/test/java/com/subhub/app/appmode/DeviceStartViewModelTest.java": "unit_device_start_view_model",
    b"app/src/test/java/com/subhub/app/runtime/DeviceStartEntryFixture.java": "unit_device_start_entry_fixture",
    b"app/src/test/java/com/subhub/app/service/InternalLimitsAdmissionTest.java": "unit_internal_limits_admission",
}
# Match only an English javac error header. Multiline source excerpts, carets,
# symbol/location/required/found details and arbitrary messages are never emitted.
JAVAC_ERRORS = (
    (rb"cannot find symbol", "cannot_find_symbol"),
    (rb"package [A-Za-z0-9_$.]+ does not exist", "missing_package"),
    (rb"incompatible types: [^\r\n]+", "incompatible_types"),
    (rb"(?:method|constructor) [^\r\n]+ cannot be applied to given types;", "argument_mismatch"),
    (rb"no suitable (?:method|constructor) found for [^\r\n]+", "no_suitable_overload"),
    (rb"method does not override or implement a method from a supertype", "invalid_override"),
    (rb"reference to [^\r\n]+ is ambiguous", "ambiguous_reference"),
    (rb"[^\r\n]+ has (?:private|protected) access in [^\r\n]+", "inaccessible_member"),
    (rb"[^\r\n]+ is not public in [^\r\n]+; cannot be accessed from outside package", "inaccessible_type"),
    (rb"non-static [^\r\n]+ cannot be referenced from a static context", "non_static_reference"),
    (rb"unreported exception [^\r\n]+; must be caught or declared to be thrown", "unreported_exception"),
    (rb"(?:variable|method|constructor|class) [^\r\n]+ is already defined in [^\r\n]+", "duplicate_definition"),
    (rb"[^\r\n]+ is not abstract and does not override abstract method [^\r\n]+", "missing_implementation"),
    (rb"[^\r\n]+ is abstract; cannot be instantiated", "abstract_instantiation"),
    (rb"cannot access [^\r\n]+", "cannot_access_class"),
    (rb"missing return statement", "missing_return"),
    (rb"(?:illegal start of (?:expression|type)|not a statement|reached end of file while parsing|"
     rb"(?:';'|'\)'|'\}'|'\]'|'\('|'\{'|<identifier>) expected)", "syntax"),
)
JAVAC_PATTERNS = tuple((re.compile(pattern), label) for pattern, label in JAVAC_ERRORS)
JAVAC_LINE = re.compile(rb"([^ \t\r\n][^\r\n]*\.java):([0-9]+): error: ([^\r\n]*)")
JAVAC_ABSOLUTE_PATH = re.compile(rb"/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+")


EXCEPTION_LINE = re.compile(
    rb"[ \t]*(?:(?:Caused by|Suppressed): )?([A-Za-z0-9_.$]{1,256})(?::(.*))?")
TASK_LINE = re.compile(rb"> Task ([A-Za-z0-9_:]+)(?: (FAILED|UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE))?")
LINT_LINE = re.compile(rb"([^\r\n]+):[0-9]{1,7}: (?:Error|Fatal): [^\r\n]* \[([A-Za-z][A-Za-z0-9]{0,63})\]")


class Diagnostics:
    def __init__(self):
        self.result = {"schema": 1, "messagesOmitted": True, "truncated": False,
                       "sanitizerFailed": False, "exceptionSectionSeen": False, "unknownException": False,
                       "exceptions": [], "resources": [], "tasks": {}, "lint": [], "lintSources": [],
                       "javac": []}
        self.in_exception = False

    def add(self, key, label, limit=None):
        values = self.result[key]
        if label not in values:
            if len(values) < (MAX_CATEGORIES if limit is None else limit):
                values.append(label)
            else:
                self.result["truncated"] = True

    def accept_javac(self, line):
        error = JAVAC_LINE.fullmatch(line)
        if not error:
            return
        source = JAVAC_SOURCES.get(error[1])
        if source is None and JAVAC_ABSOLUTE_PATH.fullmatch(error[1]):
            # Accept a Linux checkout prefix without using or publishing it.
            # Do not normalize traversal, escapes, URLs or malformed paths.
            if not any(part in (b".", b"..") for part in error[1].split(b"/")):
                for path, label in JAVAC_SOURCES.items():
                    if error[1].endswith(b"/" + path):
                        source = label
                        break
        category = "unknown"
        for pattern, label in JAVAC_PATTERNS:
            if pattern.fullmatch(error[3]):
                category = label
                break
        # Unknown sources and invalid/out-of-range lines carry no numeric data.
        number = None
        if source and len(error[2]) <= 6 and not error[2].startswith(b"0"):
            value = int(error[2])
            if 1 <= value <= MAX_JAVAC_LINE:
                number = value
        self.add("javac", {"category": category, "source": source or "unknown", "line": number},
                 limit=MAX_JAVAC_DIAGNOSTICS)

    def accept(self, line):
        if line == b"* Exception is:":
            self.in_exception = True
            self.result["exceptionSectionSeen"] = True
            return
        if line.startswith((b"* ", b"BUILD ")):
            self.in_exception = False
        if not self.in_exception:
            self.accept_javac(line)
            lint = LINT_LINE.fullmatch(line)
            if lint and lint[2] in LINT_ISSUES:
                self.add("lint", LINT_ISSUES[lint[2]])
                for path, label in LINT_SOURCES.items():
                    if lint[1] == path or lint[1].endswith(b"/" + path):
                        self.add("lintSources", label)
                        break
            task = TASK_LINE.fullmatch(line)
            if task and task[1] in TASKS:
                key = TASKS[task[1]]
                if self.result["tasks"].get(key) != "failed":
                    self.result["tasks"][key] = STATUSES[task[2]]
            return
        error = EXCEPTION_LINE.fullmatch(line)
        if not error:
            return
        label = EXCEPTIONS.get(error[1])
        if label is None:
            self.result["unknownException"] = True
            return
        self.add("exceptions", label)
        # Only recognize fixed resource phrases at the end of a known I/O error.
        # The message and any path preceding the phrase are never copied out.
        if error[1] in RESOURCE_EXCEPTIONS:
            message = (error[2] or b"").strip()
            for phrase, category in RESOURCE_MESSAGES.items():
                if message == phrase or message.endswith((b": " + phrase, b"(" + phrase + b")")):
                    self.add("resources", category)


def summarize(stream):
    diagnostic = Diagnostics()
    pending = bytearray()
    dropping = False

    def consume():
        try:
            diagnostic.accept(bytes(pending).rstrip(b"\r"))
        except Exception:
            # A parser failure must not close the pipe early and SIGPIPE Gradle.
            # Stop parsing, but continue draining every chunk through EOF.
            diagnostic.result["sanitizerFailed"] = True

    while True:
        chunk = stream.read(CHUNK_BYTES)
        if not chunk:
            break
        if diagnostic.result["sanitizerFailed"]:
            continue
        pieces = chunk.split(b"\n")
        for index, piece in enumerate(pieces):
            if not dropping:
                if len(pending) + len(piece) > MAX_LINE_BYTES:
                    diagnostic.result["truncated"] = True
                    pending.clear()
                    dropping = True
                else:
                    pending.extend(piece)
            if index < len(pieces) - 1:
                if not dropping:
                    consume()
                pending.clear()
                dropping = False
                if diagnostic.result["sanitizerFailed"]:
                    break
    if pending and not dropping and not diagnostic.result["sanitizerFailed"]:
        consume()
    return diagnostic.result


def emit(stream, output):
    try:
        result = summarize(stream)
        payload = json.dumps({"gradleDiagnostic": result}, separators=(",", ":"), sort_keys=True) + "\n"
        if len(payload.encode("utf-8")) > MAX_OUTPUT_BYTES:
            raise ValueError("Diagnostic output exceeded its bound")
    except Exception:
        # Never expose the error text or a Python traceback from this reporter.
        # Unexpected parsing/serialization faults also leave the producer's pipe
        # open until EOF. An unreadable pipe cannot be recovered by this helper.
        try:
            while stream.read(CHUNK_BYTES):
                pass
        except Exception:
            pass
        result = {"schema": 1, "messagesOmitted": True, "sanitizerFailed": True}
        payload = '{"gradleDiagnostic":{"schema":1,"messagesOmitted":true,"sanitizerFailed":true}}\n'
    try:
        output.write(payload)
        output.flush()
    except Exception:
        return FAILURE_EXIT
    return FAILURE_EXIT if result["sanitizerFailed"] else 0


if __name__ == "__main__":
    sys.exit(emit(sys.stdin.buffer, sys.stdout))
