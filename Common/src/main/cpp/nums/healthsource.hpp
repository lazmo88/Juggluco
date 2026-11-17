// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
namespace healthblood {
// Local-only metadata in unused Numdata header bytes 200..231. Existing fields
// end at byte187 and the first medical row starts at256; mirror packets do not
// transmit this header. An incarnation is not a reading ID or provenance claim.
struct SourceToken {
    uint64_t hi=0,lo=0,epoch=0;
    bool operator==(const SourceToken&) const = default;
};
inline constexpr size_t metadataOffset=200,metadataSize=32;
inline constexpr uint32_t metadataMagic=0x48434232,metadataVersion=1;
// Separate from startup's truncation journal at byte20: that repair must never
// turn an interrupted initialization into a complete Health Connect inventory.
inline constexpr size_t resetOffset=232;
inline constexpr uint32_t resetPending=0x48435250;
inline uint32_t resetMarker(const unsigned char *header) {
    uint32_t marker;std::memcpy(&marker,header+resetOffset,sizeof(marker));return marker;
}
template<class Random,class Flush>
bool sourceToken(unsigned char *header,size_t size,bool advance,SourceToken &out,
        Random random,Flush flush) {
    if(!header||size<metadataOffset+metadataSize)return false;
    auto *p=header+metadataOffset;
    std::array<unsigned char,metadataSize> before;
    std::memcpy(before.data(),p,before.size());
    uint32_t magic,version;
    std::memcpy(&magic,p,4);std::memcpy(&version,p+4,4);
    SourceToken token;
    if(magic==0 && version==0) {
        // Refuse to overwrite unknown nonzero reserved data.
        for(auto byte:before)if(byte)return false;
        uint64_t identity[2];if(!random(identity,sizeof(identity)))return false;
        token={identity[0],identity[1],1};
        if(!(token.hi||token.lo))return false;
    } else {
        if(magic!=metadataMagic||version!=metadataVersion)return false;
        std::memcpy(&token.hi,p+8,8);std::memcpy(&token.lo,p+16,8);std::memcpy(&token.epoch,p+24,8);
        if(!(token.hi||token.lo)||!token.epoch||token.epoch>uint64_t(std::numeric_limits<int64_t>::max()))return false;
        if(!advance){out=token;return true;}
        if(token.epoch==uint64_t(std::numeric_limits<int64_t>::max()))return false;
        ++token.epoch;
    }
    std::memcpy(p,&metadataMagic,4);std::memcpy(p+4,&metadataVersion,4);
    std::memcpy(p+8,&token.hi,8);std::memcpy(p+16,&token.lo,8);std::memcpy(p+24,&token.epoch,8);
    if(!flush()) {std::memcpy(p,before.data(),before.size());return false;}
    out=token;return true;
}
// Caller holds the store lock. The marker and new scope share one header flush,
// before callers may mutate identity, first position or any clinical rows.
template<class Random,class Flush>
bool beginReset(unsigned char *header,size_t size,SourceToken &out,Random random,Flush flush) {
    if(!header||size<resetOffset+sizeof(uint32_t))return false;
    const auto before=resetMarker(header);
    if(before&&before!=resetPending)return false;
    std::memcpy(header+resetOffset,&resetPending,sizeof(resetPending));
    if(sourceToken(header,size,true,out,random,flush))return true;
    std::memcpy(header+resetOffset,&before,sizeof(before));
    return false;
}
}
