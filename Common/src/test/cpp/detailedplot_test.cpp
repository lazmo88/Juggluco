// Standalone: c++ -std=c++17 -Wall -Wextra -Werror detailedplot_test.cpp -o /path/to/test
#include "../../main/cpp/curve/detailedplot.hpp"
#include <cassert>
#include <cmath>
#include <iostream>

static void near(double actual,double expected) { assert(std::abs(actual-expected)<1e-6); }
int main() {
    // Arbitrary mathematical coordinates, not glucose fixtures or patient data.
    for(double density:{1.0,2.75,4.0}) for(auto size:{std::pair<double,double>{360,620},{620,300},{160,120}}) {
        const auto p=DetailedPlot::layout(size.first*density,size.second*density,0,0,0,0,density,14*density,18*density,8*density);
        assert(p.left>0 && p.right<size.first*density && p.top>0 && p.bottom<size.second*density);
        assert(p.width()>0 && p.height()>0);
        assert(p.contains(p.left,p.top));
        assert(p.contains(p.right,p.bottom));
        assert(!p.contains(p.left-1,p.top));
        assert(p.contains(p.left-8*density,p.bottom+8*density,8*density));
        assert(!p.contains(p.right+9*density,p.top,8*density));
        near(p.timeX(10000,10000,20000),p.left);
        near(p.timeX(20000,10000,20000),p.right);
        near(p.valueY(400,400,1600),p.bottom);
        near(p.valueY(2000,400,1600),p.top);
        for(double fraction:{0.0,0.2,0.5,0.83,1.0}) {
            const double x=p.left+fraction*p.width();
            near(p.fraction(x),fraction);
            const double focus=10000+fraction*10000;
            const double start=p.zoomStart(10000,10000,4000,x);
            near(p.timeX(focus,start,start+4000),x);
            // A drag moves all plotted samples by exactly the physical delta.
            const double delta=23*density;
            const double moved=p.panStart(10000,10000,delta);
            near(p.timeX(focus,moved,moved+10000),x-delta);
        }
        near(p.fraction(p.left-100),0);
        near(p.fraction(p.right+100),1);
    }
    std::cout << "PASS detailed plot endpoints, inverse hit coordinates, pan and zoom pivot (9 layouts)\n";
}
