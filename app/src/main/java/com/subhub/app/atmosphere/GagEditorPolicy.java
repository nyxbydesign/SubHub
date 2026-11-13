package com.subhub.app.atmosphere;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

final class GagEditorPolicy {
    private GagEditorPolicy() {}

    static boolean allowsReplacement(EditorInfo editor, String ownPackage) {
        return editor != null && allowsReplacement(editor.packageName, ownPackage, editor.inputType, editor.imeOptions);
    }

    static boolean allowsReplacement(String editorPackage, String ownPackage, int inputType, int imeOptions) {
        if (editorPackage == null || ownPackage.equals(editorPackage)) return false;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                || (inputType & (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)) != 0) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_NORMAL
                || variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                || variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE;
    }
}
