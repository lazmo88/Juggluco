package tk.glucodata;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import tk.glucodata.m3.M3Page;

import java.util.Date;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Chrome for the native detailed graph. The EGL surface and its touch coordinates
 * stay together; the surface is laid out BETWEEN the controls, never behind them.
 * No readings, calibration, thresholds or saved graph colours are changed here. */
public final class FullGraph {
private static FullGraph current;
private final MainActivity act;
private final GlucoseCurve curve;
private final Handler handler=new Handler(Looper.getMainLooper());
private FrameLayout root;
private LinearLayout top,bottom;
private TextView period,legend,gestures,emptyBody;
private LinearLayout emptyActions;
private android.graphics.drawable.Drawable contentBackground;
private boolean contentBackgroundSet;
private MaterialToolbar toolbar;
private MaterialCardView empty;
private boolean active,obscured,dark;
private int left,topInset,right,bottomInset;
private final Runnable tick=new Runnable() {
    @Override public void run() {
        if(!active||obscured||!root.isAttachedToWindow()||root.getWindowVisibility()!=View.VISIBLE) return;
        updatePeriod();
        handler.postDelayed(this,500);
        }
    };

private FullGraph(MainActivity act) {
    this.act=act;
    curve=act.curve;
    build();
    }
private int dp(float n) { return M3Page.dp(act,n); }
private int color(Context ctx,int attr) { return M3Page.color(ctx,attr); }

public static void attach(MainActivity act) {
    detach();
    if(act.curve!=null) current=new FullGraph(act);
    }
public static void detach() {
    if(current==null) return;
    FullGraph old=current;
    current=null;
    old.handler.removeCallbacksAndMessages(null);
    old.setMargins(0,0,0,0);
    old.curve.queueEvent(() -> Natives.detailedGraphStyle(false,0,0,0,0));
    tk.glucodata.settings.Settings.removeContentView(old.root);
    }
public static boolean isChrome(View view) { return current!=null&&current.root==view; }
public static boolean isActive() { return current!=null&&current.active; }
public static void update(boolean visible) {
    if(current!=null) current.show(visible);
    }
public static void obscure(boolean value) {
    if(current==null||current.obscured==value) return;
    current.obscured=value;
    current.root.setVisibility(current.active&&!value?View.VISIBLE:View.GONE);
    current.handler.removeCallbacks(current.tick);
    if(current.active&&!value) current.handler.post(current.tick);
    }

private MaterialButton icon(Context ctx,int drawable,int description,Runnable click) {
    MaterialButton b=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
    b.setIconResource(drawable);
    b.setIconTint(ColorStateList.valueOf(color(ctx,com.google.android.material.R.attr.colorOnSurface)));
    b.setContentDescription(act.getString(description));
    androidx.appcompat.widget.TooltipCompat.setTooltipText(b,act.getString(description));
    b.setOnClickListener(v -> { click.run(); act.requestRender(); handler.postDelayed(this::updatePeriod,100); });
    return b;
    }
private TextView text(Context ctx,int appearance) {
    TextView v=new TextView(ctx);
    v.setTextAppearance(appearance);
    v.setTextColor(color(ctx,com.google.android.material.R.attr.colorOnSurfaceVariant));
    return v;
    }
private void build() {
    Context ctx=M3Page.themed(act);
    dark=Natives.getInvertColors();
    root=new FrameLayout(ctx) {
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            handler.removeCallbacks(tick);
            if(visibility==View.VISIBLE&&active&&!obscured) handler.post(tick);
        }
    };
    // Non-clickable gaps pass through to the native curve underneath.
    top=new LinearLayout(ctx);
    top.setOrientation(LinearLayout.VERTICAL);
    top.setBackgroundColor(color(ctx,com.google.android.material.R.attr.colorSurface));
    top.setClickable(true);
    toolbar=new MaterialToolbar(ctx);
    toolbar.setTitle(R.string.fg_title);
    toolbar.setTitleTextColor(color(ctx,com.google.android.material.R.attr.colorOnSurface));
    toolbar.setNavigationIcon(R.drawable.ic_jm_arrow_back);
    toolbar.setNavigationIconTint(color(ctx,com.google.android.material.R.attr.colorOnSurface));
    toolbar.setNavigationContentDescription(R.string.fg_dashboard);
    toolbar.setNavigationOnClickListener(v -> Dashboard.returnToDashboard(act));
    toolbar.getMenu().add(0,1,0,R.string.search).setIcon(R.drawable.ic_jm_search).setShowAsAction(2);
    toolbar.getMenu().add(0,2,1,R.string.jm_open_menu).setIcon(R.drawable.ic_jm_menu).setShowAsAction(2);
    for(int i=0;i<toolbar.getMenu().size();i++) androidx.core.view.MenuItemCompat.setIconTintList(toolbar.getMenu().getItem(i),ColorStateList.valueOf(color(ctx,com.google.android.material.R.attr.colorOnSurface)));
    toolbar.setOnMenuItemClickListener(item -> {
        if(item.getItemId()==1) curve.startsearch();
        else Menus.show(act);
        return true;
        });
    top.addView(toolbar,new LinearLayout.LayoutParams(MATCH_PARENT,dp(56)));
    LinearLayout timebar=new LinearLayout(ctx);
    timebar.setGravity(Gravity.CENTER_VERTICAL);
    timebar.setPadding(dp(8),0,dp(8),0);
    timebar.addView(icon(ctx,R.drawable.ic_jm_chevron_left,R.string.fg_previous, Natives::prevscr),new LinearLayout.LayoutParams(dp(48),dp(48)));
    period=text(ctx,com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
    period.setGravity(Gravity.CENTER);
    period.setMaxLines(2);
    period.setContentDescription(act.getString(R.string.fg_date));
    period.setOnClickListener(v -> curve.startdatepick(Natives.getstarttime()));
    period.setClickable(true);
    timebar.addView(period,new LinearLayout.LayoutParams(0,dp(48),1));
    timebar.addView(icon(ctx,R.drawable.ic_jm_chevron_right,R.string.fg_next,Natives::nextscr),new LinearLayout.LayoutParams(dp(48),dp(48)));
    MaterialButton now=new MaterialButton(ctx,null,com.google.android.material.R.attr.borderlessButtonStyle);
    now.setText(R.string.fg_now);
    now.setOnClickListener(v -> { Natives.settonow(); act.requestRender(); });
    timebar.addView(now,new LinearLayout.LayoutParams(WRAP_CONTENT,dp(48)));
    top.addView(timebar);
    root.addView(top,new FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT,Gravity.TOP));

    bottom=new LinearLayout(ctx);
    bottom.setOrientation(LinearLayout.VERTICAL);
    bottom.setBackgroundColor(color(ctx,com.google.android.material.R.attr.colorSurface));
    bottom.setClickable(true);
    legend=text(ctx,com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
    legend.setGravity(Gravity.CENTER);
    legend.setPadding(dp(16),dp(6),dp(16),0);
    bottom.addView(legend,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    gestures=text(ctx,com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    gestures.setText(R.string.fg_gestures);
    gestures.setGravity(Gravity.CENTER);
    gestures.setPadding(dp(12),dp(4),dp(12),dp(8));
    bottom.addView(gestures,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    root.addView(bottom,new FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT,Gravity.BOTTOM));

    empty=new MaterialCardView(ctx);
    empty.setRadius(dp(24));
    empty.setCardElevation(0);
    empty.setStrokeWidth(0);
    empty.setStrokeColor(color(ctx,com.google.android.material.R.attr.colorOutlineVariant));
    empty.setCardBackgroundColor(color(ctx,com.google.android.material.R.attr.colorSurfaceContainerLow));
    // Informational only: empty periods must still accept chart pan/zoom gestures.
    empty.setClickable(false);
    empty.setFocusable(false);
    LinearLayout message=new LinearLayout(ctx);
    message.setOrientation(LinearLayout.VERTICAL);
    message.setGravity(Gravity.CENTER);
    message.setPadding(dp(24),dp(24),dp(24),dp(24));
    TextView title=text(ctx,com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    title.setTextColor(color(ctx,com.google.android.material.R.attr.colorOnSurface));
    title.setText(R.string.fg_empty_title);
    title.setGravity(Gravity.CENTER);
    message.addView(title);
    emptyBody=text(ctx,com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    emptyBody.setText(R.string.fg_empty_body);
    emptyBody.setGravity(Gravity.CENTER);
    emptyBody.setPadding(0,dp(8),0,dp(16));
    message.addView(emptyBody);
    emptyActions=new LinearLayout(ctx);
    emptyActions.setGravity(Gravity.CENTER);
    MaterialButton sensors=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
    sensors.setText(R.string.fg_sensors);
    sensors.setOnClickListener(v -> Menus.openSensors(act));
    emptyActions.addView(sensors,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1));
    MaterialButton meters=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
    meters.setText(R.string.fg_meters);
    meters.setOnClickListener(v -> Menus.openMeters(act));
    LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1);
    mp.setMarginStart(dp(12));
    emptyActions.addView(meters,mp);
    message.addView(emptyActions,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    empty.addView(message,new FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT,Gravity.CENTER));
    FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT);
    root.addView(empty,ep);
    empty.setVisibility(View.GONE);
    root.setVisibility(View.GONE);
    act.addContentView(root,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    top.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if(active) layoutCurve(); });
    bottom.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if(active) layoutCurve(); });
    ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets) -> {
        Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        left=bars.left; topInset=bars.top; right=bars.right; bottomInset=bars.bottom;
        top.setPadding(left,topInset,right,0);
        bottom.setPadding(left,0,right,bottomInset);
        if(active) root.post(this::layoutCurve);
        return insets;
        });
    ViewCompat.requestApplyInsets(root);
    }
private void show(boolean visible) {
    boolean changed=active!=visible;
    active=visible;
    if(visible&&dark!=Natives.getInvertColors()) {
        tk.glucodata.settings.Settings.removeContentView(root);
        build();
        changed=true;
        }
    if(Dashboard.getFullGraph()) toolbar.setNavigationIcon(R.drawable.ic_jm_arrow_back);
    else toolbar.setNavigationIcon((android.graphics.drawable.Drawable)null);
    root.setVisibility(visible&&!obscured?View.VISIBLE:View.GONE);
    if(changed) {
        Context ctx=M3Page.themed(act);
        int surface=color(ctx,com.google.android.material.R.attr.colorSurface);
        int ink=color(ctx,com.google.android.material.R.attr.colorOnSurfaceVariant);
        int grid=color(ctx,com.google.android.material.R.attr.colorOutlineVariant);
        int accent=color(ctx,com.google.android.material.R.attr.colorPrimary);
        if(curve.getParent() instanceof View) {
            View content=(View)curve.getParent();
            if(visible) {
                if(!contentBackgroundSet) {
                    contentBackground=content.getBackground();
                    contentBackgroundSet=true;
                    }
                content.setBackgroundColor(surface);
                }
            else if(contentBackgroundSet) {
                content.setBackground(contentBackground);
                contentBackgroundSet=false;
                contentBackground=null;
                }
            }
        curve.queueEvent(() -> Natives.detailedGraphStyle(visible,surface,ink,grid,accent));
        if(visible) root.post(this::layoutCurve);
        else setMargins(0,0,0,0);
        handler.removeCallbacks(tick);
        if(visible&&!obscured) handler.post(tick);
        act.requestRender();
        }
    }
private void layoutCurve() {
    int l=left+dp(12), t=top.getHeight()+dp(8), r=right+dp(12), b=bottom.getHeight()+dp(8);
    setMargins(l,t,r,b);
    FrameLayout.LayoutParams ep=(FrameLayout.LayoutParams)empty.getLayoutParams();
    if(ep.leftMargin!=l||ep.topMargin!=t||ep.rightMargin!=r||ep.bottomMargin!=b) {
        ep.setMargins(l,t,r,b);
        empty.setLayoutParams(ep);
        }
    boolean compact=root.getHeight()-t-b<dp(240);
    emptyBody.setVisibility(compact?View.GONE:View.VISIBLE);
    emptyActions.setVisibility(compact?View.GONE:View.VISIBLE);
    }
private void setMargins(int l,int t,int r,int b) {
    if(!(curve.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
    FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)curve.getLayoutParams();
    if(p.leftMargin==l&&p.topMargin==t&&p.rightMargin==r&&p.bottomMargin==b) return;
    p.setMargins(l,t,r,b);
    curve.setLayoutParams(p);
    curve.post(() -> { curve.syncViewportInsets(); curve.requestRender(); });
    }
private void updatePeriod() {
    if(!active) return;
    long[] state=Natives.detailedGraphState();
    if(state==null||state.length<4||state[0]==0||state[3]==0) {
        empty.setVisibility(View.GONE);
        return;
        }
    long from=state[0]*1000L, until=state[1]*1000L;
    java.text.DateFormat date=DateFormat.getMediumDateFormat(act);
    java.text.DateFormat time=DateFormat.getTimeFormat(act);
    String a=date.format(new Date(from)), b=date.format(new Date(until));
    period.setText(a+(a.equals(b)?"":" – "+b)+"\n"+time.format(new Date(from))+" – "+time.format(new Date(until)));
    period.setContentDescription(act.getString(R.string.fg_date)+", "+period.getText());
    empty.setVisibility(state[2]==0?View.VISIBLE:View.GONE);
    String unit=act.getString(Natives.getunit()==1?R.string.fg_mmol:R.string.fg_mgdl);
    legend.setText(act.getString(state[2]==0?R.string.fg_empty_legend:R.string.fg_legend,unit));
    gestures.setText(state[2]==0?R.string.fg_empty_gestures:R.string.fg_gestures);
    }
}
