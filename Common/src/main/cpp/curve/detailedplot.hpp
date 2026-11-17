#pragma once
#include <algorithm>

// Presentation-only coordinates. No readings, unit conversion or thresholds.
// Shared by drawing, point hit testing and gestures; independently host-testable.
struct DetailedPlot {
    double left,top,right,bottom;
    double width() const { return right-left; }
    double height() const { return bottom-top; }
    bool contains(double x,double y,double margin=0) const {
        return x>=left-margin && x<=right+margin && y>=top-margin && y<=bottom+margin;
    }
    double fraction(double x) const { return std::clamp((x-left)/width(),0.0,1.0); }
    double timeX(double t,double start,double end) const { return left+(t-start)*width()/(end-start); }
    double valueY(double value,double minimum,double range) const { return bottom-(value-minimum)*height()/range; }
    double panStart(double start,double duration,double dx) const { return start+duration*dx/width(); }
    double zoomStart(double start,double duration,double nextDuration,double pivot) const {
        return start+fraction(pivot)*(duration-nextDuration);
    }
    static DetailedPlot layout(double width,double height,double leftInset,double topInset,double rightInset,double bottomInset,double density,double font,double line,double radius) {
        // Keep the entire endpoint/found-point circle inside the data clip.
        const double left=leftInset+12*density+radius;
        const double top=topInset+std::max(28*density,line*1.7)+radius;
        const double right=std::max(left+1,width-rightInset-std::max(64*density,font*4.5)-radius);
        const double bottom=std::max(top+1,height-bottomInset-std::max(32*density,line*1.8)-radius);
        return {left,top,right,bottom};
    }
};
