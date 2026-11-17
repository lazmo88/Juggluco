#pragma once
#include <array>
#include <algorithm>
#include <cstdint>
#include <vector>
#include <cstring>
#include <cmath>

// JMI1 big-endian, no compiler layout/padding. This is persisted by Java in the
// SAME synchronous preference commit as first explicit Add consent. Preparation
// is read-only; this blob is provenance, not native authorization by itself.
namespace meterbinding {
constexpr size_t rawAt=86,maxRaw=64,maxSize=rawAt+maxRaw+4;
inline uint32_t get(const uint8_t *p) {
    return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3];
}
inline void put(uint8_t *p,uint32_t n) {p[0]=n>>24;p[1]=n>>16;p[2]=n>>8;p[3]=n;}
inline uint32_t checksum(const uint8_t *p,size_t n) {
    uint32_t out=0x811c9dc5u;for(size_t i=0;i<n;++i)out=(out^p[i])*0x01000193u;return out;
}
inline uint32_t bits(float value) {uint32_t n;memcpy(&n,&value,4);return n;}
inline float value(uint32_t n) {float f;memcpy(&f,&n,4);return f;}
inline bool packet(const uint8_t *raw,size_t n,MeterData &glu) {
    if(!raw||n<14||n>maxRaw)return false;
    memcpy(&glu,raw,std::min(n,sizeof(glu)));
    const auto &t=glu.time;
    if(!glu.typeAndLocationPresent||t.year<1970||t.year>2106||t.month<1||t.month>12||
       t.day<1||t.hour<0||t.hour>23||t.minute<0||t.minute>59||t.second<0||t.second>59)return false;
    constexpr int days[]={31,28,31,30,31,30,31,31,30,31,30,31};
    const bool leap=t.year%4==0&&(t.year%100!=0||t.year%400==0);
    return t.day<=days[t.month-1]+(t.month==2&&leap);
}
inline bool convertedValue(const MeterData &glu,uint32_t unit,float &out) {
    const float mg=glu.getmgdL();
    if(!std::isfinite(mg)||mg<=0)return false;
    if(unit==1)out=std::round(convertmultmmol*100.0f*mg)*.1f;
    else if(unit==2)out=std::roundf(mg);
    else return false;
    return std::isfinite(out)&&out>0;
}
inline bool deviceBytes(const GlucoseMeter &meter,uint8_t *out) {
    const size_t n=strnlen(meter.deviceName,maxDeviceName);
    if(n==0||n>=maxDeviceName)return false;
    memset(out,0,45);memcpy(out,meter.deviceName,n);memcpy(out+39,meter.deviceAddress,6);return true;
}
inline bool labelBytes(uint32_t blood,uint8_t *out) {
    const int count=settings->getlabelcount();
    if(count<=0||count>maxvarnr||blood>=maxvarnr||blood>=uint32_t(count))return false;
    const auto label=settings->getlabel(blood);
    if(label.empty()||label.size()>11)return false;
    memset(out,0,12);memcpy(out,label.data(),label.size());return true;
}
inline bool prepare(const uint8_t *raw,size_t n,const GlucoseMeter &meter,std::vector<uint8_t> &out) {
    MeterData glu{};if(!packet(raw,n,glu))return false;
    const int64_t original=glu.time.getLocaltime();
    const int64_t tim=original+int64_t(meter.timeoffset)+int64_t(glu.getTimeoffset())*60;
    float stored;
    if(original<=0||original>UINT32_MAX||tim<=0||tim>UINT32_MAX||
       !convertedValue(glu,settings->data()->unit,stored))return false;
    out.assign(rawAt+n+4,0);memcpy(out.data(),"JMI1",4);
    put(out.data()+4,tim);put(out.data()+8,bits(stored));
    put(out.data()+12,settings->data()->bloodvar);put(out.data()+16,settings->data()->unit);
    put(out.data()+20,original);put(out.data()+24,uint32_t(meter.timeoffset));
    if(!deviceBytes(meter,out.data()+28)||!labelBytes(settings->data()->bloodvar,out.data()+73))return false;
    out[85]=n;memcpy(out.data()+rawAt,raw,n);
    put(out.data()+out.size()-4,checksum(out.data(),out.size()-4));return true;
}
inline bool consume(const uint8_t *raw,size_t n,const uint8_t *b,size_t len,const GlucoseMeter &meter,
                    uint32_t &tim,float &stored,uint32_t &blood) {
    if(!b||len<rawAt+14+4||len>maxSize||memcmp(b,"JMI1",4)||b[85]!=n||len!=rawAt+n+4||
       get(b+len-4)!=checksum(b,len-4))return false;
    MeterData glu{};if(!packet(raw,n,glu)||memcmp(b+rawAt,raw,n))return false;
    blood=get(b+12);const uint32_t unit=get(b+16);
    // Never reinterpret an old binding using a new configured unit/type/name.
    if(blood!=settings->data()->bloodvar||unit!=settings->data()->unit)return false;
    std::array<uint8_t,45> device{};std::array<uint8_t,12> label{};
    if(!deviceBytes(meter,device.data())||memcmp(b+28,device.data(),device.size())||
       !labelBytes(blood,label.data())||memcmp(b+73,label.data(),label.size()))return false;
    const uint32_t original=get(b+20);const int32_t offset=static_cast<int32_t>(get(b+24));
    const int64_t corrected=int64_t(original)+offset+int64_t(glu.getTimeoffset())*60;
    tim=get(b+4);stored=value(get(b+8));float expected;
    return original>0&&corrected>0&&corrected<=UINT32_MAX&&tim==corrected&&
           convertedValue(glu,unit,expected)&&bits(expected)==bits(stored);
}
}
