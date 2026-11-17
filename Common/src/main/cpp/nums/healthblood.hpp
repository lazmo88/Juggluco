// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>
#include "healthsource.hpp"

// Copy all accepted blood-label amounts, not the sensor stream or pending meter
// decisions. Caller holds the source-registry lock while the per-store writer
// locks keep mappings and rows stable. False is never an authoritative empty set.
template<class Store>
bool healthBloodSnapshot(const std::vector<Store*> &stores,int unit,int blood,
        std::vector<int64_t> &out) {
    out.clear();
    if((unit!=1&&unit!=2)||blood<0||stores.size()!=2||stores[0]==stores[1])return false;
    std::vector<std::unique_lock<std::mutex>> locks;
    for(auto *store:stores) {
        if(!store)return false;
        locks.emplace_back(store->nummutex);
    }
    unsigned slots=0;
    for(const auto *store:stores) {
        if(!store->mappingsAvailable()||store->mirrorRepairPending())return false;
        const int slot=store->getindex();
        if(slot<0||slot>1||(slots&(1U<<slot)))return false;
        slots|=1U<<slot;
        const auto first=store->getfirstpos(),last=store->getlastpos();
        if(first<0||last<first||size_t(last)>store->capacity())return false;
    }
    std::vector<int64_t> next{2,unit,blood,2};
    for(auto *store:stores) {
        healthblood::SourceToken token;
        if(!store->healthInventoryToken(token))return false;
        // Logical local/mirror slot, not the legacy raw header identifier (both
        // are zero on a fresh install). UUID+epoch identifies the incarnation.
        next.insert(next.end(),{store->getindex(),int64_t(token.hi),int64_t(token.lo),
            int64_t(token.epoch),store->getfirstpos(),store->getlastpos(),
            store->getfirstpos()==0?1:0,0});
        const auto countIndex=next.size()-1;
        const int count=store->getlastpos()-store->getfirstpos();
        if(!count)continue;
        const auto *rows=store->begin();
        for(int i=0;i<count;++i) {
            const auto *row=rows+i;
            if(row->type&0x80000000U)continue;
            // Blank/invalid live slots are not proof of a deleted reading.
            if(!store->valid(row))return false;
            if(row->type!=uint32_t(blood))continue;
            if(!std::isfinite(row->value)||row->value<=0)return false;
            uint32_t bits;
            static_assert(sizeof(bits)==sizeof(row->value));
            std::memcpy(&bits,&row->value,sizeof(bits));
            next.push_back(row->time);
            next.push_back(bits);
            ++next[countIndex];
        }
    }
    out.swap(next);
    return true;
}
