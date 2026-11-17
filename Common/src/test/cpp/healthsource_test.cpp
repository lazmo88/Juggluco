// SPDX-License-Identifier: GPL-3.0-or-later
#include <array>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <limits>
#include "nums/healthsource.hpp"
int main() {
    std::array<unsigned char,256> header{};
    uint64_t seed=100;
    auto random=[&](void *p,size_t n){assert(n==16);uint64_t words[]{++seed,++seed};memcpy(p,words,n);return true;};
    int commits=0;
    auto flush=[&](){++commits;return true;};
    healthblood::SourceToken a{},b{};
    assert(healthblood::sourceToken(header.data(),header.size(),false,a,random,flush));
    assert(a.hi!=0 && a.lo!=0 && a.epoch==1 && commits==1);
    assert(healthblood::sourceToken(header.data(),header.size(),false,b,random,flush));
    assert(a==b && commits==1);
    // Source incarnation survives reload; reset advances even if source ID stays.
    auto reload=header;
    assert(healthblood::sourceToken(reload.data(),reload.size(),true,b,random,flush));
    assert(a.hi==b.hi && a.lo==b.lo && b.epoch==2);
    // Failure must abort before a mirror reset and retain original metadata.
    const auto before=header;
    assert(!healthblood::sourceToken(header.data(),header.size(),true,b,random,[]{return false;}));
    assert(header==before);
    // Metadata never touches medical rows or the existing number header fields.
    for(size_t i=0;i<header.size();++i)if(i<200||i>=232)assert(header[i]==0);
    std::array<unsigned char,256> replacement{};
    assert(healthblood::sourceToken(replacement.data(),replacement.size(),false,b,random,flush));
    assert(b.hi!=a.hi || b.lo!=a.lo);
    std::array<unsigned char,256> unknown{};unknown[200]=1;auto untouched=unknown;
    assert(!healthblood::sourceToken(unknown.data(),unknown.size(),false,b,random,flush));assert(unknown==untouched);
    assert(!healthblood::sourceToken(header.data(),100,false,b,random,flush));
    uint64_t overflow=uint64_t(std::numeric_limits<int64_t>::max());memcpy(header.data()+224,&overflow,8);
    assert(!healthblood::sourceToken(header.data(),header.size(),true,b,random,flush));
    std::array<unsigned char,256> reset{};
    const auto pristine=reset;
    int resets=0;
    auto resetFlush=[&](){++resets;assert(healthblood::resetMarker(reset.data())==healthblood::resetPending);return true;};
    assert(!healthblood::beginReset(reset.data(),reset.size(),b,[](void*,size_t){return false;},resetFlush));
    assert(reset==pristine&&resets==0);
    assert(!healthblood::beginReset(reset.data(),reset.size(),b,random,[]{return false;}));
    assert(reset==pristine);
    assert(healthblood::beginReset(reset.data(),reset.size(),a,random,resetFlush));
    assert(a.epoch==1&&resets==1);
    auto latched=reset;
    assert(!healthblood::beginReset(reset.data(),reset.size(),b,random,[]{return false;}));
    assert(reset==latched); // Existing pending must survive failed retry too.
    assert(healthblood::beginReset(reset.data(),reset.size(),b,random,resetFlush));
    assert(b.epoch==2&&resets==2);
    uint32_t unknownMarker=7;memcpy(reset.data()+232,&unknownMarker,4);latched=reset;
    assert(!healthblood::beginReset(reset.data(),reset.size(),b,random,resetFlush));assert(reset==latched);
    assert(!healthblood::beginReset(reset.data(),235,b,random,resetFlush));
    puts("PASS durable source incarnation, reset epoch+pending single flush, RNG/flush rollback, latched retry, unknown-header protection");
}
