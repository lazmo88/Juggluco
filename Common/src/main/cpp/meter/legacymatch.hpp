// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <vector>

// Read-only search for an Add accepted by an older version without a saved
// conversion binding. Rows carry no unit history, so a candidate is any live
// blood-label row near the reading whose stored value equals the reading
// converted to mmol/L OR mg/dL (bit-exact, same formula as every meter save).
// Never writes, never decides: Java shows the result and the user resolves.
namespace legacymatch {
struct Hit { int64_t time; int slot; int unit; };
// window: seconds either side of `expected` (corrected time with today's offset).
// False when any store cannot be read safely; never an authoritative "absent".
template<class Store>
bool find(const std::vector<Store*> &stores,uint32_t blood,uint32_t expected,uint32_t window,
          float mmol,float mgdl,std::vector<Hit> &out) {
    out.clear();
    if(stores.empty())return false;
    const uint32_t from=expected>window?expected-window:0;
    const uint32_t to=expected+window<expected?UINT32_MAX:expected+window;
    uint32_t mmolBits,mgBits;
    std::memcpy(&mmolBits,&mmol,4);std::memcpy(&mgBits,&mgdl,4);
    std::vector<Hit> found;
    for(auto *store:stores) {
        if(!store)return false;
        std::lock_guard<std::mutex> lock(store->nummutex);
        if(!store->mappingsAvailable()||store->mirrorRepairPending())return false;
        const int first=store->getfirstpos(),last=store->getlastpos();
        if(first<0||last<first||size_t(last)>store->capacity())return false;
        const auto *row=store->firstnotless(from);
        const auto *end=store->end();
        for(;row<end&&row->time<=to;++row) {
            if(row->type&0x80000000U)continue; // removed
            if(row->type!=blood||!store->valid(row))continue;
            uint32_t bits;std::memcpy(&bits,&row->value,4);
            const int unit=bits==mmolBits?1:bits==mgBits?2:0;
            if(unit)found.push_back({int64_t(row->time),store->getindex(),unit});
        }
    }
    out.swap(found); // only a complete search is ever reported
    return true;
}
}
