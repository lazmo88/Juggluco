// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic rows only: legacy Add recovery search (meter/legacymatch.hpp).
#include <cassert>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <vector>
#include "meter/legacymatch.hpp"
struct Row { uint32_t time, type; float value; bool live=true; };
struct Store {
    std::mutex nummutex;
    std::vector<Row> rows;
    bool ready=true, repairing=false;
    int slot=0;
    int getindex() const { return slot; }
    bool mappingsAvailable() const { return ready; }
    bool mirrorRepairPending() const { return repairing; }
    int getfirstpos() const { return 0; }
    int getlastpos() const { return int(rows.size()); }
    size_t capacity() const { return rows.size(); }
    const Row *end() const { return rows.data()+rows.size(); }
    const Row *firstnotless(uint32_t t) const {
        return std::lower_bound(rows.data(),end(),t,[](const Row &r,uint32_t v){return r.time<v;});
    }
    bool valid(const Row *r) const { return r->live && r->time; }
};
int main() {
    const uint32_t meter=1763131380; // synthetic corrected meter time
    const float mmol=9.8f, mg=177.0f;
    Store local, mirror; mirror.slot=1;
    local.rows={
        {meter-90000,3,9.8f},          // outside 24 h window
        {meter-30,3,9.8f},             // same reading, small clock drift
        {meter,3,9.7f},                // different value: never a match
        {meter+10,0,9.8f},             // other label (carbs): never a match
        {meter+20,0x80000003,9.8f},    // removed row
        {meter+18000,3,177.0f},        // stored in mg/dL, five hours later
    };
    mirror.rows={{meter+60,3,9.8f,false}}; // blank/invalid slot is not a match
    std::vector<Store*> stores{&local,&mirror};
    std::vector<legacymatch::Hit> hits;
    assert(legacymatch::find(stores,3,meter,86400,mmol,mg,hits));
    assert(hits.size()==2);
    assert(hits[0].time==meter-30&&hits[0].unit==1&&hits[0].slot==0);
    assert(hits[1].time==meter+18000&&hits[1].unit==2);
    puts("PASS value/label/window/removed/invalid filtering; both stored units recognised");
    assert(legacymatch::find(stores,3,meter,10,mmol,mg,hits)&&hits.empty());
    puts("PASS empty within a narrow window is reported as found-nothing, not failure");
    mirror.repairing=true;
    assert(!legacymatch::find(stores,3,meter,86400,mmol,mg,hits)&&hits.empty());
    mirror.repairing=false; local.ready=false;
    assert(!legacymatch::find(stores,3,meter,86400,mmol,mg,hits));
    local.ready=true;
    assert(!legacymatch::find(std::vector<Store*>{},3,meter,86400,mmol,mg,hits));
    assert(!legacymatch::find(std::vector<Store*>{&local,nullptr},3,meter,86400,mmol,mg,hits));
    puts("PASS unreadable/repairing/missing stores fail closed (never 'not found')");
    assert(legacymatch::find(stores,3,40,86400,mmol,mg,hits)); // window clamps at zero
    assert(legacymatch::find(stores,3,UINT32_MAX-5,86400,mmol,mg,hits)&&hits.empty());
    puts("PASS window arithmetic clamps at both ends");
}
