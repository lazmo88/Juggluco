#!/usr/bin/env python3
"""Host-only execution of the actual native mapping/rotation method bodies.
No APK, NDK, emulator, sensor files or health data are used.
Run: python3 Common/src/test/cpp/native_plot_contract_test.py
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2] / "main/cpp/curve"
header = (root / "JCurve.hpp").read_text()
app = (root / "appcurve.cpp").read_text()


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("JCurve::", "")


methods = [
    method(header, "inline bool currentOrientationAsLandscape() const"),
    method(header, "inline bool portraitReadable() const"),
    method(header, "inline int graphRotationMode() const"),
    method(header, "DetailedPlot detailedPlot() const"),
    method(header, "bool inDetailedClip(double x,double y) const"),
    method(header, "auto gettrans(uint32_t starttime,uint32_t endtime) {"),
    method(header, "inline float\tnumtypeheight(const int type)  {"),
    method(app, "bool JCurve::detailedGraphActive() const"),
    method(app, "void JCurve::withbottom()"),
    method(app, "void JCurve::setSystemBars(int left,int top,int right,int bottom)"),
    method(app, "void JCurve::resizescreen(int widthin, int heightin,int initscreenwidth)"),
    method(app, "void    JCurve::startstep(NVGcontext* avg,const NVGcolor &col)"),
]
source = r'''
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cassert>
#include <iostream>
#include <utility>
#include "detailedplot.hpp"
#define LOGGER(...) ((void)0)
#define JUGGLUCO_APP 1
// Renderer calls are no-ops: this test exercises native layout, not raster output.
struct NVGcontext {};
struct NVGcolor { float r=0,g=0,b=0,a=1; };
#define glViewport(...) ((void)0)
#define glClearColor(...) ((void)0)
#define glClear(...) ((void)0)
#define glEnable(...) ((void)0)
#define glBlendFunc(...) ((void)0)
#define glDisable(...) ((void)0)
#define nvgTranslate(...) ((void)0)
#define nvgRotate(...) ((void)0)
extern const int maxmenulen=8;
bool showpers=false;
int numlist=0;
int laneHeights[]={0,1,2,3,4};
int *numheights=laneHeights;
int shownlabels=5;
struct { const void *scan=nullptr; } scantoshow;
struct { bool rotateText=true; int orientation=0; } options;
struct Settings { auto data() const { return &options; } } settingsObject;
auto settings=&settingsObject;
struct JCurve {
    bool detailedPhone=false,physicalNanoVG=false,legacyPhoneFrame=false,portrait=false;
    int surfacewidth=0,surfaceheight=0,width=0,height=0;
    int physicalbarleft=0,physicalbartop=0,physicalbarright=0,physicalbarbottom=0;
    int statusbarleft=0,statusbarright=0,statusbarheight=0,systemBarsGraphRotation=-1;
    float dleft=0,dright=0,dtop=0,dbottom=0,dwidth=0,dheight=0;
    float density=2.75,smallsize=38.5,smallfontlineheight=49.5;
    float foundPointRadius=22,pointRadius=11,menutextheight=100,textheight=100;
    float facetimefontsize=0,facetimey=0,timelen=180;
    int gmin=400,grange=1600;
    std::atomic<int> displayRotationState{0};
    void startstepNVG(NVGcontext*,int,int) {}
'''
source += "\n".join(methods) + r'''
};
static void near(double a,double b) { assert(std::abs(a-b)<1e-5); }
int main() {
    JCurve curve;
    for(int rotation=0;rotation<4;++rotation) for(bool rotateText:{false,true}) {
        options.rotateText=rotateText;
        curve.displayRotationState.store(rotation);
        curve.detailedPhone=false;
        curve.resizescreen(990,1705,0);
        const int legacyMode=curve.graphRotationMode();
        assert(curve.width==1705 && curve.height==990);
        curve.detailedPhone=true;
        curve.resizescreen(990,1705,0); // identical surface, transient mode toggle
        assert(curve.graphRotationMode()==0 && !curve.portraitReadable());
        assert(curve.width==990 && curve.height==1705);
        const auto p=curve.detailedPlot();
        const auto [tx,ty]=curve.gettrans(10000,20000);
        near(tx(10000),p.left); near(tx(20000),p.right);
        near(ty(400),p.bottom); near(ty(2000),p.top);
        near(tx(15000),p.left+p.width()/2);
        near(ty(1200),p.top+p.height()/2);
        // First and last annotation lanes keep complete label/value text in
        // the plot; the old half-line inset clipped the first label's top.
        const double first=curve.numtypeheight(0), last=curve.numtypeheight(4);
        assert(first-1.4*curve.smallsize-curve.smallfontlineheight/2>=p.top-1e-4);
        assert(last+curve.smallfontlineheight/2<=p.bottom+1e-4);
        // Same transform used by hit tests (outside the painting scope).
        near(p.fraction(tx(15000)),0.5);
        assert(curve.inDetailedClip(p.left,p.top));
        assert(!curve.inDetailedClip(p.left-curve.foundPointRadius-1,p.top));
        for(int exclusion=0;exclusion<4;++exclusion) {
            showpers=exclusion==0;
            numlist=exclusion==1;
            scantoshow.scan=exclusion==2?&curve:nullptr;
            curve.legacyPhoneFrame=exclusion==3;
            assert(!curve.detailedGraphActive());
            curve.resizescreen(990,1705,0);
            assert(curve.graphRotationMode()==legacyMode);
            assert(curve.width==1705 && curve.height==990);
            showpers=false; numlist=0; scantoshow.scan=nullptr; curve.legacyPhoneFrame=false;
        }
        curve.resizescreen(990,1705,0);
        assert(curve.graphRotationMode()==0 && curve.width==990);
        // startstep itself repairs geometry when views change without resize.
        scantoshow.scan=&curve;
        curve.startstep(nullptr,NVGcolor{});
        assert(curve.width==1705 && curve.height==990);
        scantoshow.scan=nullptr;
        curve.startstep(nullptr,NVGcolor{});
        assert(curve.width==990 && curve.height==1705);
        curve.legacyPhoneFrame=true;
        curve.startstep(nullptr,NVGcolor{});
        assert(curve.width==1705);
        curve.legacyPhoneFrame=false;
        curve.startstep(nullptr,NVGcolor{});
        assert(curve.width==990);
        curve.detailedPhone=false;
        curve.resizescreen(990,1705,0);
        assert(curve.graphRotationMode()==legacyMode && curve.width==1705);
        const auto [oldx,oldy]=curve.gettrans(10000,20000);
        near(oldx(10000),0); near(oldx(20000),curve.dwidth);
        near(oldy(400),curve.dheight-curve.pointRadius);
        near(oldy(2000),curve.pointRadius);
        assert(curve.inDetailedClip(-100000,-100000)); // no new legacy hit clipping
    }
    curve.detailedPhone=true;
    curve.resizescreen(1705,990,0);
    assert(curve.graphRotationMode()==0 && curve.width==1705 && curve.height==990);
    curve.setSystemBars(10,20,30,40);
    const auto inset=curve.detailedPlot();
    assert(inset.left>10 && inset.top>20 && inset.right<1675 && inset.bottom<950);
    std::cout << "PASS actual native transforms, all four rotation states, transient resize, scan/error/stats/list exclusions, legacy endpoints and inset layout\n";
}
'''
base = os.environ.get("TMPDIR", "/var/lib/hermes/cache/scratch")
with tempfile.TemporaryDirectory(prefix="juggluco-plot-contract-", dir=base) as tmp:
    cpp = Path(tmp) / "contract.cpp"
    cpp.write_text(source)
    binary = Path(tmp) / "contract"
    subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-I", str(root), str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
