// SPDX-License-Identifier: GPL-3.0-or-later
#include <cassert>
#include <cstdio>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>
#include <limits>
#include "nums/healthsource.hpp"
struct Row { uint32_t time, type; float value; bool live=true; };
struct Store {
    std::mutex nummutex;
    std::vector<Row> rows;
    bool ready=true, repairing=false;
    int first=0;
    int64_t identity=0;
    int slot=1;
    healthblood::SourceToken token{100,200,1};
    bool healthInventoryToken(healthblood::SourceToken &out) {out=token;return true;}
    int64_t getnewident() const {return identity;}
    int getindex() const {return slot;}
    bool mappingsAvailable() const { return ready; }
    bool mirrorRepairPending() const { return repairing; }
    int getfirstpos() const { return first; }
    int getlastpos() const { return int(rows.size()); }
    size_t capacity() const { return rows.size(); }
    const Row *begin() const { return rows.data()+first; }
    const Row *end() const { return rows.data()+rows.size(); }
    bool valid(const Row *r) const { return r->live && r->time; }
};
#include "nums/healthblood.hpp"
int main() {
    Store here, mirror;
    mirror.slot=0;mirror.token.hi=101;
    here.rows={{946684800,3,5.6f},{1700000000,0,24.0f},{1700000001,0x80000003,7.2f,false}};
    mirror.rows={{946684800,3,5.6f},{1700000002,3,6.1f}};
    std::vector<Store*> stores{&here,&mirror};
    std::vector<int64_t> out;
    assert(healthBloodSnapshot(stores,1,3,out));
    assert(out.size()==26 && out[0]==2 && out[1]==1 && out[2]==3 && out[3]==2);
    // Fresh mirror header and local header both have raw identity zero. The
    // logical slots must still be distinct; UUID+epoch carries incarnation.
    assert(here.getnewident()==0&&mirror.getnewident()==0);
    assert(out[4]==1&&out[14]==0);
    mirror.slot=1;assert(!healthBloodSnapshot(stores,1,3,out));
    mirror.slot=2;assert(!healthBloodSnapshot(stores,1,3,out));
    mirror.slot=0;assert(healthBloodSnapshot(stores,1,3,out));
    assert(out[12]==946684800 && out[22]==946684800 && out[24]==1700000002);
    uint32_t bits; float value=5.6f; memcpy(&bits,&value,4);
    assert(out[13]==int64_t(bits));
    here.first=1;
    assert(healthBloodSnapshot(stores,1,3,out) && out[10]==0);
    here.first=0;
    auto saved=mirror.rows;mirror.rows={{0,0,0}};
    assert(!healthBloodSnapshot(stores,1,3,out) && out.empty());
    mirror.rows=saved;
    assert(!healthBloodSnapshot(std::vector<Store*>{&here,&here},1,3,out));
    // Full history, duplicates retained for the policy's deterministic dedupe;
    // carbs and removed/unaccepted records never become export candidates.
    assert(!healthBloodSnapshot(stores,0,3,out));
    assert(!healthBloodSnapshot(stores,1,-1,out));
    assert(!healthBloodSnapshot(std::vector<Store*>{&here},1,3,out));
    mirror.ready=false;
    assert(!healthBloodSnapshot(stores,1,3,out));
    mirror.ready=true; mirror.repairing=true;
    assert(!healthBloodSnapshot(stores,1,3,out));
    mirror.repairing=false;
    here.rows.push_back({1700000003,3,std::numeric_limits<float>::quiet_NaN()});
    assert(!healthBloodSnapshot(stores,1,3,out));
    here.rows.clear(); mirror.rows.clear();
    assert(healthBloodSnapshot(stores,2,3,out) && out.size()==20);
    puts("Health blood snapshot: source coverage, pruning, placeholder, all-history, filtering, repair safety passed");
}
