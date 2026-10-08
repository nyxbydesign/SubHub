package com.subhub.app.capture;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityCustomImagesBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.util.PrimaryHeader;

/** Secondary library surface reusing the editor embedded in Censor settings. */
public final class CustomImagesActivity extends AppCompatActivity {
    private CensorImageEditor images;
    private ControllerEditMode editMode;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityCustomImagesBinding binding = ActivityCustomImagesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.custom_images_title, true);
        images = new CensorImageEditor(this, binding.buttonAdd, binding.status, binding.imageList);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        editMode = ControllerEditMode.bind(this, PrimaryHeader.editLockButton(binding.getRoot()),
                editing -> images.refresh());
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
    }

    @Override protected void onDestroy() {
        if (images != null) images.close();
        super.onDestroy();
    }
}
