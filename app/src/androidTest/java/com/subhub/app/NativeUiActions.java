package com.subhub.app;

import static org.junit.Assert.assertTrue;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.matcher.ViewMatchers;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

/** Real-touch setup: scroll and prove clearance; callers still inject Espresso clicks. */
public final class NativeUiActions {
    private NativeUiActions() { }
    public static ViewAction revealAboveNavigation() {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() {
                return ViewMatchers.isDescendantOfA(Matchers.instanceOf(ScrollView.class));
            }
            @Override public String getDescription() { return "reveal native control above floating navigation"; }
            @Override public void perform(UiController ui, View view) {
                ViewParent parent = view.getParent();
                while (!(parent instanceof ScrollView)) parent = parent.getParent();
                ScrollView scroll = (ScrollView) parent;
                Rect target = new Rect();
                view.getDrawingRect(target);
                scroll.offsetDescendantRectToMyCoords(view, target);
                scroll.scrollTo(0, Math.max(0, target.top - scroll.getHeight() / 3));
                ui.loopMainThreadUntilIdle();
                Rect visible = new Rect();
                assertTrue(view.getGlobalVisibleRect(visible));
                View navigation = view.getRootView().findViewById(R.id.bottom_navigation);
                Rect nav = new Rect();
                if (navigation != null && navigation.getGlobalVisibleRect(nav)) {
                    assertTrue("Tap target must clear floating navigation", visible.bottom <= nav.top);
                }
            }
        };
    }
}
