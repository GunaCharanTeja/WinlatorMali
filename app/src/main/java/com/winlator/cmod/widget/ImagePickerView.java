package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.core.WineThemeManager;

import java.io.File;

public class ImagePickerView extends View implements View.OnClickListener {
    private final android.graphics.drawable.Drawable iconDrawable;

    public ImagePickerView(Context context) {
        this(context, null);
    }

    public ImagePickerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImagePickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        iconDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_image_picker);

        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0 || iconDrawable == null) return;

        float rectSize = height - UnitUtils.dpToPx(12);
        float startX = (width - rectSize) * 0.5f - UnitUtils.dpToPx(16);
        float startY = (height - rectSize) * 0.5f;

        iconDrawable.setBounds((int) startX, (int) startY, (int) (startX + rectSize), (int) (startY + rectSize));
        iconDrawable.draw(canvas);
    }

    @Override
    public void onClick(View anchor) {
        final Context context = getContext();
        final File userWallpaperFile = WineThemeManager.getUserWallpaperFile(context);

        View view = LayoutInflater.from(context).inflate(R.layout.image_picker_view, null);
        ImageView imageView = view.findViewById(R.id.ImageView);

        if (userWallpaperFile.isFile()) {
            imageView.setImageBitmap(BitmapFactory.decodeFile(userWallpaperFile.getPath()));
        }
        else imageView.setImageResource(R.drawable.wallpaper);

        final PopupWindow[] popupWindow = {null};
        View browseButton = view.findViewById(R.id.BTBrowse);
        browseButton.setOnClickListener((v) -> {
            MainActivity activity = (MainActivity)context;
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            popupWindow[0].dismiss();
            activity.startActivityForResult(intent, MainActivity.OPEN_IMAGE_REQUEST_CODE);
        });

        View removeButton = view.findViewById(R.id.BTRemove);
        if (userWallpaperFile.isFile()) {
            removeButton.setVisibility(View.VISIBLE);
            removeButton.setOnClickListener((v) -> {
                FileUtils.delete(userWallpaperFile);
                popupWindow[0].dismiss();
            });
        }

        popupWindow[0] = AppUtils.showPopupWindow(anchor, view, 200, 240);
    }
}
