package tk.glucodata;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.FitCenterStrategy;

/*
 * Camera scanner for sensor data matrix and mirror QR codes (Menu > Photo).
 * Material 3 dark camera screen: top app bar with close and torch, full screen
 * preview, the instruction in a card at the bottom.  Scanning and the result
 * handling are the library's (CaptureActivity/CaptureManager), unchanged.
 * Register with @style/zxing_CaptureTheme and select via IntentIntegrator.
 */
public class MyCaptureActivity extends CaptureActivity {
    private boolean torchOn=false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Window w=getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(Build.VERSION.SDK_INT>=21) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        super.onCreate(savedInstanceState);
    }

    private int dp(float v) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,getResources().getDisplayMetrics());
    }

    private static void tintIcon(MenuItem item) {
        if(item.getIcon()!=null)
            item.getIcon().mutate().setTint(Color.WHITE);
    }

    @Override
    protected DecoratedBarcodeView initializeContent() {
        // CaptureActivity reads the intent after this method returns.
        getIntent().putExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN);
        final Context ctx=new ContextThemeWrapper(this,R.style.Juggluco_M3_Dark);

        final DecoratedBarcodeView decorated = new DecoratedBarcodeView(this);
        final BarcodeView preview = decorated.getBarcodeView();
        preview.setUseTextureView(false);
        preview.setPreviewScalingStrategy(new FitCenterStrategy());
        decorated.getViewFinder().setLaserVisibility(false);
        /* The library's own prompt (edge to edge, no padding) is replaced by the card below. */
        final String prompt=getIntent().getStringExtra(Intents.Scan.PROMPT_MESSAGE);
        decorated.getStatusView().setVisibility(View.GONE);

        preview.addStateListener(new CameraPreview.StateListener() {
            private boolean parametersQueued;

            @Override
            public void previewSized() {
                if (parametersQueued) {
                    return;
                }
                final CameraInstance instance = preview.getCameraInstance();
                if (instance == null || !instance.isOpen()) {
                    return;
                }
                parametersQueued = true;

                // JourneyApps 4.3.0: queue after configuration, before preview
                // startup. The parameter lambda executes on the camera thread.
                preview.changeCameraParameters(parameters -> {
                    parameters.setRecordingHint(true);
                    return parameters;
                });
            }

            @Override
            public void previewStarted() {
            }

            @Override
            public void previewStopped() {
                parametersQueued = false;
            }

            @Override
            public void cameraError(Exception error) {
                // CaptureManager already displays the camera-error dialog.
            }

            @Override
            public void cameraClosed() {
                parametersQueued = false;
            }
        });

        FrameLayout root=new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        root.addView(decorated,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));

        /* Top app bar over a scrim so it stays readable on a bright preview. */
        final MaterialToolbar bar=new MaterialToolbar(ctx);
        bar.setTitle(R.string.jm_scan_title);
        bar.setTitleTextColor(Color.WHITE);
        bar.setNavigationIcon(R.drawable.ic_jm_close);
        bar.setNavigationIconTint(Color.WHITE);
        bar.setNavigationContentDescription(R.string.closename);
        bar.setNavigationOnClickListener(v -> finish());
        bar.setBackgroundColor(0x99000000);
        if(getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            final MenuItem torch=bar.getMenu().add(R.string.jm_scan_torch_on);
            torch.setIcon(R.drawable.ic_jm_flashlight_on);
            torch.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            tintIcon(torch);
            torch.setOnMenuItemClickListener(item -> {
                torchOn=!torchOn;
                if(torchOn)
                    decorated.setTorchOn();
                else
                    decorated.setTorchOff();
                item.setIcon(torchOn?R.drawable.ic_jm_flashlight_off:R.drawable.ic_jm_flashlight_on);
                item.setTitle(torchOn?R.string.jm_scan_torch_off:R.string.jm_scan_torch_on);
                tintIcon(item);
                return true;
            });
        }
        root.addView(bar,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP));

        /* Instruction card at the bottom. */
        MaterialCardView card=new MaterialCardView(ctx);
        card.setCardBackgroundColor(0xE6202124);
        card.setStrokeWidth(0);
        card.setRadius(dp(16));
        TextView text=new TextView(ctx);
        text.setText(prompt!=null?prompt:getString(R.string.photomessage));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        text.setLineSpacing(0,1.2f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(20),dp(16),dp(20),dp(16));
        card.addView(text);
        final FrameLayout.LayoutParams cardParams=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM);
        cardParams.setMargins(dp(16),0,dp(16),dp(24));
        root.addView(card,cardParams);

        final int toolbarHeight=dp(64);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int top=insets.getSystemWindowInsetTop();
            int left=insets.getSystemWindowInsetLeft();
            int right=insets.getSystemWindowInsetRight();
            int bottom=insets.getSystemWindowInsetBottom();
            bar.setPadding(left,top,right,0);
            ViewGroup.LayoutParams lp=bar.getLayoutParams();
            lp.height=toolbarHeight+top;
            bar.setLayoutParams(lp);
            cardParams.setMargins(left+dp(16),0,right+dp(16),bottom+dp(24));
            card.setLayoutParams(cardParams);
            return insets;
        });

        setContentView(root);
        return decorated;
    }
}
