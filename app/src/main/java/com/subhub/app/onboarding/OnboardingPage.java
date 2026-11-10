package com.subhub.app.onboarding;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A walkthrough page describes a role; it never grants that role or changes a setting. */
public final class OnboardingPage {
    public enum Kind { STORY, APPEARANCE, WALLET, APPS, SECURITY, PERMISSIONS }
    public enum Scene { CONNECT_DOM, CONNECT_SUB, SUB_TASKS, HARDCORE, CONTRACTS,
        CREATE_TASK, REVIEW_TASK, SWITCH_ROLES, CENSOR_SETTINGS, WALLET_RULES, LOCAL_FEATURES, SECURITY, PERMISSIONS }
    public enum Workspace { SUB, DOM, DEVICE }
    public enum Action { NONE, PAIR_DOM, PAIR_SUB, HARDCORE }

    public static final class Detail {
        public final int title, body;
        private Detail(int title, int body) { this.title = title; this.body = body; }
    }

    public final String id;
    public final int title, description, actionLabel;
    public final Scene scene;
    public final Workspace workspace;
    public final Kind kind;
    public final Action action;
    public final List<Detail> details;

    private OnboardingPage(String id, int title, int description, Scene scene, Workspace workspace,
            Kind kind, int actionLabel, Action action, List<Detail> details) {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid setup page identity");
        this.id = id; this.title = title; this.description = description;
        this.scene = Objects.requireNonNull(scene); this.workspace = Objects.requireNonNull(workspace);
        this.kind = Objects.requireNonNull(kind); this.actionLabel = actionLabel;
        this.action = Objects.requireNonNull(action);
        this.details = Collections.unmodifiableList(new java.util.ArrayList<>(details));
    }

    public static OnboardingPage story(String id, int title, int description, Scene scene,
            Workspace workspace, Detail... details) {
        return new OnboardingPage(id, title, description, scene, workspace, Kind.STORY,
                0, Action.NONE, Arrays.asList(details));
    }

    public static OnboardingPage setup(String id, int title, int description, Scene scene,
            Workspace workspace, Kind kind) {
        return new OnboardingPage(id, title, description, scene, workspace, kind,
                0, Action.NONE, Collections.emptyList());
    }

    public static Detail detail(int title, int body) { return new Detail(title, body); }

    public OnboardingPage action(int label, Action action) {
        return new OnboardingPage(id, title, description, scene, workspace, kind,
                label, action, details);
    }
}
