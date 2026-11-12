/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2(+), Libre 3(+), Dexcom G7/ONE+,        */
/*      Sibionics GS1Sb and GS3, Accu-Chek SmartGuide, CareSens Air and              */
/*      Aidex X sensors.                                                             */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Thu Oct 15 12:44:39 CEST 2026                                                */

package tk.glucodata;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import tk.glucodata.settings.AppTheme;

public class DynamicThemeUtils {
private static final String LOG_ID="DynamicThemeUtils";

    public static int resolveAttributeColor(Context ctx, int attrId, int defaultColor) {
        if (attrId == 0) return defaultColor;

        TypedValue val = new TypedValue();

        if (ctx.getTheme().resolveAttribute(attrId, val, true)) {
            try {
                if (val.resourceId != 0) {
                    return ContextCompat.getColor(ctx, val.resourceId);
                }

                if (val.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                        val.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return val.data;
                }
            } catch (Throwable e) {
                Log.stack(LOG_ID, "resolveAttributeColor", e);
            }
        }
        return defaultColor;
    }
    /* Smallest corner radius of the themed buttons: Material 3 buttons are
     * rounded, so small radii (some themes, or a random radius chosen when
     * dark mode became a profile setting) no longer give square buttons. */
    public static final int MINRADIUS_DP=16;

    private static int attrColor(Context ctx,String name,int defaultColor) {
        int id=ctx.getResources().getIdentifier(name,"attr",ctx.getPackageName());
        return resolveAttributeColor(ctx,id,defaultColor);
        }

    private static boolean isLight(Context ctx) {
        TypedValue value=new TypedValue();
        return ctx.getTheme().resolveAttribute(R.attr.isAppLightTheme,value,true)&&value.data!=0;
        }

    private static int opaque(int color) {
        return color|0xFF000000;
        }

    private static int windowColor(Context ctx,boolean light) {
        return opaque(resolveAttributeColor(ctx,android.R.attr.windowBackground,light?Color.WHITE:Color.BLACK));
        }

    private static int textColor(Context ctx,boolean light) {
        return opaque(resolveAttributeColor(ctx,android.R.attr.textColorPrimary,light?Color.BLACK:Color.WHITE));
        }

    /* Material 3 tonal button container: the theme's button colour mixed into
     * the window background, so every theme keeps its own hue. */
    public static int tonalContainer(Context ctx) {
        final boolean light=isLight(ctx);
        final int window=windowColor(ctx,light);
        final int accent=opaque(attrColor(ctx,"colorButtonBackground",resolveAttributeColor(ctx,android.R.attr.colorButtonNormal,Color.GRAY)));
        float frac=light?0.24f:0.30f;
        int container=ColorUtils.blendARGB(window,accent,frac);
        while(ColorUtils.calculateContrast(container,window)<1.2&&frac<0.6f) {
            frac+=0.08f;
            container=ColorUtils.blendARGB(window,accent,frac);
            }
        if(ColorUtils.calculateContrast(container,window)<1.2)  // accent too close to the background
            container=ColorUtils.blendARGB(container,textColor(ctx,light),light?0.08f:0.14f);
        return opaque(container);
        }

    /* Label colour on the tonal container: a dark (light mode) or light (dark
     * mode) tone of the button colour, like Material's on-container colours,
     * with at least 7:1 contrast where the theme allows it. */
    public static int tonalText(Context ctx,int container) {
        final boolean light=isLight(ctx);
        final int text=textColor(ctx,light);
        final int accent=opaque(attrColor(ctx,"colorButtonBackground",text));
        int color=accent;
        for(float frac=0.4f;ColorUtils.calculateContrast(color,container)<7.0&&frac<=1.0f;frac+=0.1f)
            color=ColorUtils.blendARGB(accent,text,frac);
        if(ColorUtils.calculateContrast(color,container)<4.5)
            color=text;
        return opaque(color);
        }

    /* Text colours for themed buttons, enabled and disabled. */
    public static ColorStateList buttonTextColors(Context ctx) {
        final boolean light=isLight(ctx);
        final int enabled=tonalText(ctx,tonalContainer(ctx));
        final int disabled=attrColor(ctx,"colorDisabledText",ColorUtils.blendARGB(windowColor(ctx,light),textColor(ctx,light),0.38f));
        return new ColorStateList(
                new int[][] {
                        new int[] {-android.R.attr.state_enabled},
                        new int[] {}
                },
                new int[] {disabled, enabled});
        }

    public static InsetDrawable createDynamicButton(Context ctx, int radiusDp, boolean isOval) {
        final boolean light=isLight(ctx);
        int cBg = tonalContainer(ctx);
        /* Material 3 disabled container: on-surface at 12% */
        int cDisabledBg = opaque(ColorUtils.blendARGB(windowColor(ctx,light),textColor(ctx,light),0.12f));
        int cHigh = attrColor(ctx,"colorControlHighlight", 0x33FFFFFF);

        float density = ctx.getResources().getDisplayMetrics().density;
        int radiusPx = (int) (Math.max(radiusDp,MINRADIUS_DP) * density);
        int shape = isOval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE;

        GradientDrawable normShape = new GradientDrawable();
        normShape.setShape(shape);
        normShape.setColor(cBg);
        if (!isOval) normShape.setCornerRadius(radiusPx);

        GradientDrawable disShape = new GradientDrawable();
        disShape.setShape(shape);
        disShape.setColor(cDisabledBg);
        if (!isOval) disShape.setCornerRadius(radiusPx);

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{-android.R.attr.state_enabled}, disShape);
        stateList.addState(new int[]{}, normShape);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(shape);
        mask.setColor(Color.WHITE);
        if (!isOval) mask.setCornerRadius(radiusPx);

        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(cHigh), stateList, mask);

        int insetH = (int) (4 * density);
        int insetV = (int) (6 * density);
        
        return new InsetDrawable(ripple, insetH, insetV, insetH, insetV);
    }

    public static void applyTheme(View root) {
        if(Natives.getTheme() == 0) {
            return;
           }
        int radius = Natives.getradius();
        boolean isOval = Natives.getisOval();
        traverseAndStyle(root, radius, isOval, true);
       }

    public static void applyTheme(View root,int radius,boolean isOval) {
        traverseAndStyle(root, radius, isOval, true);
       }

    private static void applyDisabledTextColor(TextView text) {
        ColorStateList oldColors = text.getTextColors();
//        if(oldColors == null || oldColors.isStateful())
        if(oldColors == null)
            return;

        Context ctx = text.getContext();
        String pkg = ctx.getPackageName();
        int disabledId = ctx.getResources().getIdentifier("colorDisabledText", "attr", pkg);
        int enabledColor = oldColors.getDefaultColor();
        int disabledColor = resolveAttributeColor(ctx, disabledId, enabledColor);

        text.setTextColor(new ColorStateList(
                new int[][] {
                        new int[] {-android.R.attr.state_enabled},
                        new int[] {}
                },
                new int[] {disabledColor, enabledColor}
        ));
    }

    private static void applyCompoundButtonTint(CompoundButton button) {
        Context ctx = button.getContext();
        String pkg = ctx.getPackageName();
        int disabledId = ctx.getResources().getIdentifier("colorDisabledControl", "attr", pkg);

        int normal = resolveAttributeColor(ctx, android.R.attr.colorControlNormal, Color.GRAY);
        int activated = resolveAttributeColor(ctx, android.R.attr.colorControlActivated, normal);
        int disabled = resolveAttributeColor(ctx, disabledId, normal);

        button.setButtonTintList(new ColorStateList(
                new int[][] {
                        new int[] {-android.R.attr.state_enabled},
                        new int[] {android.R.attr.state_checked},
                        new int[] {}
                },
                new int[] {disabled, activated, normal}
        ));
    }

    private static void traverseAndStyle(View view, int radius, boolean isOval, boolean styleButtons) {
        if (view instanceof TextView)
            applyDisabledTextColor((TextView)view);

        if (view instanceof CompoundButton)
            applyCompoundButtonTint((CompoundButton)view);

        if (styleButtons && view instanceof Button && !(view instanceof CompoundButton)) {
            int pL = view.getPaddingLeft();
            int pT = view.getPaddingTop();
            int pR = view.getPaddingRight();
            int pB = view.getPaddingBottom();

            view.setBackground(createDynamicButton(view.getContext(), radius, isOval));
            ((Button)view).setTextColor(buttonTextColors(view.getContext()));

            view.setPadding(pL, pT, pR, pB);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                traverseAndStyle(group.getChildAt(i), radius, isOval, styleButtons);
            }
        }
    }
static void setTheme(Activity act) {
       int theme=Natives.getTheme();
       Log.i(LOG_ID,"getTheme()="+theme);
       if(theme!=0) {
                act.setTheme( AppTheme.values()[theme].getStyleResId());
                if(Natives.getisOval()) {
                    act.getTheme().applyStyle(R.style.ShapeOverride_Oval, true);
                    }
             }
       }
}
