#include <time.h>
#include <stdio.h>
#include <stdint.h>
#include <jni.h>
#include "fromjava.h"
#include "share/hexstr.hpp"
#include "jniclass.hpp"
#include "settings/settings.hpp"

struct getfrom {
    uint8_t reg[3]={1,3,1};
    uint16_t index;
    } __attribute__ ((packed));

auto getfromcmd(uint16_t index) {
    const getfrom from{.index=index};
    return from;
    }


static auto getcaretime(time_t tim) {
     struct tm tmbuf;
     localtime_r(&tim,&tmbuf);
     int16_t year=tmbuf.tm_year+1900;
     int8_t mon=tmbuf.tm_mon+1;
     int8_t day=tmbuf.tm_mday;
     int8_t hour=tmbuf.tm_hour;
     int8_t min=tmbuf.tm_min;
     int8_t sec=tmbuf.tm_sec;
    struct  {
        uint8_t reg[4]{0xC0,0x03,0x01,0x00};
        int16_t year;
        int8_t month;
        int8_t day;
        int8_t hour;
        int8_t min;
        int8_t sec;
        } __attribute__ ((packed)) uit{
         .year=year,
         .month=mon,
         .day=day,
         .hour=hour,
         .min=min,
         .sec=sec};
     return uit;
     }


extern "C" JNIEXPORT jbyteArray JNICALL   fromjava(careSenseTimeCMD)(JNIEnv *env, jclass cl) {
   const auto cmd=getcaretime(time(nullptr));
   #ifndef NOLOG
   {const hexstr  cmdhex(cmd);
   LOGGER("careSenseTimeCMD()=%s\n",cmdhex.str());
   }
   #endif
   constexpr const int len=sizeof(cmd);
   jbyteArray uit=env->NewByteArray(len);
   env->SetByteArrayRegion(uit, 0, len,reinterpret_cast<const jbyte*>(&cmd));
   return uit;
   }
#include "MeterData.hpp"
#include "nums/numdata.hpp"
       extern Numdata *getherenums();

extern void addCalibration(uint32_t tim,int type,Num *num,const Numdata *numdata) ;
extern void    setnumchanged(uint32_t tim);

// Lock order: MeterAsk Java lock -> meterImportMutex -> Numdata::nummutex.
// No callback to Java while either native mutex is held. Ordinary saves and
// clock/cursor accesses use the same meter lock as explicit accepted imports.
static std::mutex meterImportMutex;

#include "importbinding.hpp"
#include "legacymatch.hpp"
extern std::vector<Numdata*> numdatas;
extern std::mutex healthNumRegistryMutex;

// Read-only recovery aid for an Add accepted by an older version without a
// conversion binding. Returns {expected corrected time, then (time,slot,unit)*}
// for live blood-label rows within +-window seconds whose value equals this
// record converted to mmol/L (unit 1) or mg/dL (unit 2). Null: cannot check.
extern "C" JNIEXPORT jlongArray JNICALL fromjava(GlucoseMeterLegacyMatches)(JNIEnv *env,jclass,jint meterIndex,jbyteArray raw,jint window) {
    if(!raw||window<0||window>7*86400)return nullptr;
    const auto n=env->GetArrayLength(raw);
    if(n<14||size_t(n)>meterbinding::maxRaw)return nullptr;
    std::array<uint8_t,meterbinding::maxRaw> bytes{};
    env->GetByteArrayRegion(raw,0,n,reinterpret_cast<jbyte*>(bytes.data()));
    if(env->ExceptionCheck())return nullptr;
    int64_t expected;float mmol,mg;uint32_t blood;
    {
        std::lock_guard<std::mutex> lock(meterImportMutex);
        const auto *meter=settings->data()->getGlucoseMeter(meterIndex);
        MeterData glu{};
        if(!meter||!meterbinding::packet(bytes.data(),n,glu))return nullptr;
        expected=int64_t(glu.time.getLocaltime())+int64_t(meter->timeoffset)+int64_t(glu.getTimeoffset())*60;
        blood=settings->data()->bloodvar;
        if(expected<=0||expected>UINT32_MAX||blood>=uint32_t(maxvarnr)||
           !meterbinding::convertedValue(glu,1,mmol)||!meterbinding::convertedValue(glu,2,mg))return nullptr;
    }
    std::vector<legacymatch::Hit> hits;
    {
        // Same order as the Health Connect snapshot: registry, then per-store nummutex.
        std::lock_guard<std::mutex> registry(healthNumRegistryMutex);
        if(!legacymatch::find(numdatas,blood,uint32_t(expected),uint32_t(window),mmol,mg,hits))return nullptr;
    }
    std::vector<jlong> out{jlong(expected)};
    for(const auto &hit:hits) {out.push_back(hit.time);out.push_back(hit.slot);out.push_back(hit.unit);}
    jlongArray result=env->NewLongArray(jsize(out.size()));
    if(result)env->SetLongArrayRegion(result,0,jsize(out.size()),out.data());
    return env->ExceptionCheck()?nullptr:result;
}

extern "C" JNIEXPORT jbyteArray JNICALL fromjava(GlucoseMeterPrepareAccepted)(JNIEnv *env,jclass,jint meterIndex,jbyteArray raw) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    if(!raw)return nullptr;
    const auto n=env->GetArrayLength(raw);
    if(n<14||size_t(n)>meterbinding::maxRaw)return nullptr;
    const auto *meter=settings->data()->getGlucoseMeter(meterIndex);
    if(!meter)return nullptr;
    std::array<uint8_t,meterbinding::maxRaw> bytes{};
    env->GetByteArrayRegion(raw,0,n,reinterpret_cast<jbyte*>(bytes.data()));
    if(env->ExceptionCheck())return nullptr;
    std::vector<uint8_t> bound;
    if(!meterbinding::prepare(bytes.data(),n,*meter,bound))return nullptr;
    jbyteArray result=env->NewByteArray(bound.size());
    if(!result)return nullptr;
    env->SetByteArrayRegion(result,0,bound.size(),reinterpret_cast<const jbyte*>(bound.data()));
    return env->ExceptionCheck()?nullptr:result;
}

extern "C" JNIEXPORT jlong JNICALL fromjava(GlucoseMeterCorrectedTime)(JNIEnv *env,jclass,jint meterIndex,jbyteArray raw) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    if(!raw)return -1;
    const auto length=env->GetArrayLength(raw);
    if(length<14||size_t(length)>meterbinding::maxRaw)return -1;
    const auto *meter=settings->data()->getGlucoseMeter(meterIndex);
    if(!meter)return -1;
    std::array<uint8_t,meterbinding::maxRaw> bytes{};
    env->GetByteArrayRegion(raw,0,length,reinterpret_cast<jbyte*>(bytes.data()));
    if(env->ExceptionCheck())return -1;
    std::vector<uint8_t> binding;
    if(!meterbinding::prepare(bytes.data(),length,*meter,binding))return -1;
    return meterbinding::get(binding.data()+4);
}

extern "C" JNIEXPORT jint JNICALL fromjava(GlucoseMeterImportAccepted)(JNIEnv *env,jclass,jint meterIndex,jbyteArray raw,jbyteArray binding) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    if(settings->staticnum()||!raw||!binding)return int(MeterImportReceipt::Rejected);
    const auto length=env->GetArrayLength(raw),boundLength=env->GetArrayLength(binding);
    if(length<14||size_t(length)>meterbinding::maxRaw||boundLength<104||size_t(boundLength)>meterbinding::maxSize)
        return int(MeterImportReceipt::Rejected);
    auto *meter=settings->data()->getGlucoseMeter(meterIndex);
    if(!meter)return int(MeterImportReceipt::Rejected);
    std::array<uint8_t,meterbinding::maxRaw> bytes{};
    std::array<uint8_t,meterbinding::maxSize> bound{};
    // No VM-critical array across a mutex wait or synchronous mapped flush.
    env->GetByteArrayRegion(raw,0,length,reinterpret_cast<jbyte*>(bytes.data()));
    env->GetByteArrayRegion(binding,0,boundLength,reinterpret_cast<jbyte*>(bound.data()));
    if(env->ExceptionCheck())return int(MeterImportReceipt::Rejected);
    uint32_t tim,blood;float value;
    if(!meterbinding::consume(bytes.data(),length,bound.data(),boundLength,*meter,tim,value,blood))
        return int(MeterImportReceipt::Rejected);
    auto *numda=getherenums();
    if(!numda)return int(MeterImportReceipt::Rejected);
    const auto receipt=numda->importAcceptedMeter(tim,value,blood);
    if(receipt==MeterImportReceipt::Rejected)return int(receipt);
    const uint16_t sequence=uint16_t(bytes[1])|(uint16_t(bytes[2])<<8);
    if(tim>meter->lastTime) {
        meter->lastTime=tim;
        meter->nextIndex=uint16_t(sequence+1);
    }
    // Explicit historical import never initiates current-value calibration.
    if(backup)backup->wakebackup(wakenums);
    setnumchanged(uint32_t(time(nullptr)));
    return int(receipt);
}

extern "C" JNIEXPORT jboolean JNICALL   fromjava(GlucoseMeterSave)(JNIEnv *env, jclass cl,jint meterIndex,jbyteArray value) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    if(settings->staticnum()) {
        LOGAR("GlucoseMeterSave: staticnum don't save");
        return false;
        }
    const auto bloodvar=settings->data()->bloodvar;
    if(bloodvar>=maxvarnr) {
        LOGAR("GlucoseMeterSave: no bloodvar set");
        return false;
        }
    const auto arlen=env->GetArrayLength(value);
    if(arlen<14) {
        LOGGER("GlucoseMeterSave length (%d) <14\n",arlen);
        return false;
        }
    bool ret=false;
    {
        const CritAr  bluedata(env,value);
        const MeterData *glu=reinterpret_cast<const MeterData *>(bluedata.data());
        GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex);
        if(!meter) {
            LOGGER("GlucoseMeterSave no meter at %d\n",meterIndex);
            return false;
            }
        const auto timeoffset=glu->getTimeoffset();
        const uint32_t timeorig=glu->time.getLocaltime();
        const uint32_t timcorrected=timeorig+meter->timeoffset+timeoffset*60;
        auto mgdL=glu->getmgdL();
        #ifndef NOLOG
        char timebuf1[27],timebuf2[27];
        time_t ort=timeorig,cort=timcorrected;
        LOGGER("GlucoseMeterSave index=%d	%.2f	mg/dL	%.1f	mmol/L		timeOffsetPresent=%d	contextInfoFollows=%d	typeAndLocationPresent=%d orig=%.24s genoffset=%d specoffset=%d corrected=%s",glu->index,mgdL,mgdL/18.0,glu->timeOffsetPresent,glu->contextInfoFollows,glu->typeAndLocationPresent,ctime_r(&ort,timebuf1),meter->timeoffset,timeoffset,ctime_r(&cort,timebuf2));
        #endif
        if(timcorrected>meter->lastTime) {
            float value;
            if(settings->data()->unit==1)  {
                value=std::round(convertmultmmol*100.0f*mgdL)*.1f;
                }
            else
                 value=std::roundf(mgdL);
            Numdata *numda=getherenums();
            if(Num *num=numda->numsaveonly(timcorrected, value,bloodvar,0,true)) { 
                uint32_t now=time(nullptr);
                if(abs((int)(now-timcorrected))<60)  {
                    addCalibration( timcorrected,bloodvar,num,numda);
                    if(backup)
                        backup->wakebackup(wakenums);
                    setnumchanged(now);
                    ret=true;
                    }
                meter->nextIndex=uint16_t(glu->index+1);
                meter->lastTime=timcorrected;
                }
            }
    }
    return ret;
    }
#include "Context.hpp"
extern "C" JNIEXPORT jboolean JNICALL   fromjava(GlucoseMeterProcessContext)(JNIEnv *env, jclass cl,jint meterIndex,jbyteArray value) {
    const auto arlen=env->GetArrayLength(value);
    if(arlen<3) {
        LOGGER("GlucoseMeterSaveContext length (%d) <14\n",arlen);
        return false;
        }
    const CritAr  bluedata(env,value);
    const Context *con=reinterpret_cast<const Context *>(bluedata.data());
    #ifndef NOLOG
    const hexstr  conhex((uint8_t *)bluedata.data(),arlen);
    LOGGER("GlucoseMeterSaveContext index=%d  %s\n",con->index,conhex.str());
    #endif
    return true;
    }

extern "C" JNIEXPORT jboolean JNICALL   fromjava(GlucoseMeterSaveTime)(JNIEnv *env, jclass cl,jint meterIndex,jbyteArray value) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    const auto arlen=env->GetArrayLength(value);
    if(arlen<7) {
        LOGGER("GlucoseMeterSaveTime length (%d) <7\n",arlen);
        return false;
        }
    GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex);
    if(!meter) {
        LOGGER("GlucoseMeterSaveTime no meter at %d\n",meterIndex);
        return false;
        }
    const CritAr  bluedata(env,value);
    const time_t metertime=reinterpret_cast<const MeterTime *>(bluedata.data())->getLocaltime();
    const time_t now=time(nullptr);
    int offset=now-metertime;
    meter->timeoffset=offset;
    #ifndef NOLOG
    char timebuf[27];
    LOGGER("GlucoseMeterSaveTime offset%d now: %zu metertime: %zu %s\n",offset,now,metertime,ctime_r(&metertime,timebuf));
    #endif
    return true;
    }
extern "C" JNIEXPORT jbyteArray JNICALL   fromjava(getGlucoseMeterNewCMD)(JNIEnv *env, jclass cl,jint meterIndex) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex);
    if(!meter) {
        LOGGER("getGlucoseMeterNewCMD no meter at %d\n",meterIndex);
        return nullptr;
        }
   const auto nextIndex=meter->nextIndex;
   LOGGER("getGlucoseMeterNewCMD %d\n",nextIndex);
   auto cmd=getfromcmd(nextIndex);
   constexpr const int len=sizeof(cmd);
   jbyteArray uit=env->NewByteArray(len);
   env->SetByteArrayRegion(uit, 0, len,reinterpret_cast<const jbyte*>(&cmd));
   return uit;
    }
//
//Contour7802H6195553                   
extern int newGlucoseMeter(std::string_view scangegs);
int newGlucoseMeter(std::string_view scangegs) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        const int len=scangegs.size();
        if(len!=12) {
                LOGGER("newGlucoseMeter wrongsize %d %s\n",len,scangegs.data());
                return -1; 
                }
        if(!settings->data()->bloodvar) {
            return -2;
            }
        std::string uit;
        uit.reserve(19);
        uit.append("Contour");
        uit.append(scangegs);
        if(GlucoseMeter *meter= settings->data()->giveGlucoseMeter(uit.data(),nullptr)) {
            meter->active=true;
            int meterIndex=meter-settings->data()->glucosemeters;
            LOGGER("newGlucoseMeter %s index=%d\n",uit.data(),meterIndex);
            return meterIndex;
            }
         else {
            return -1;
            }
        }
//meter: onCharacteristicChanged 00002a52-0000-1000-8000-00805f9b34fb 06 00 01 01
//meter: onCharacteristicChanged 00002a52-0000-1000-8000-00805f9b34fb 06 00 01 06

extern void calibrateLast() ;
extern "C" JNIEXPORT jboolean JNICALL   fromjava(recordCharacteristicChanged)(JNIEnv *env, jclass cl, jbyteArray value) {
    const auto arlen=env->GetArrayLength(value);
    if(arlen<4) {
        LOGGER("Meter: recordCharacteristicChanged length (%d) <4\n",arlen);
        return false;
        }
    uint8_t dat3;
    {
    const CritAr  bluedata(env,value);
    const uint8_t *data=(const uint8_t*)bluedata.data();
    const uint8_t reg[]={6,0,1};
    if(memcmp(data,reg,sizeof(reg))) {
        LOGAR("Meter: recordCharacteristicChanged unrecognised unput");
        return false;
        }
    dat3=data[3];
    }
    switch(dat3) {
        case 1: {
            LOGAR("Meter recordCharacteristicChanged received data"); 
            calibrateLast();
            if(backup)
                 backup->wakebackup(wakenums);
            return true;
            }
        case 6: {
           LOGAR("Meter recordCharacteristicChanged received NO data"); 
           };break;
       default:
           LOGGER("Meter recordCharacteristicChanged unrecognised byte %d\n",dat3);
        }
     return false;
    }

extern "C" JNIEXPORT jint  JNICALL   fromjava(GlucoseMeterGetLastPos)(JNIEnv *env, jclass cl,jint meterIndex) {
        if(const GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                return meter->nextIndex;
                }
         return -1;
        }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterSetLastPos)(JNIEnv *env, jclass cl,jint meterIndex,jint newpos) {
        if(GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                meter->nextIndex=newpos;
                return true;
                }
         return false;
        }

void deviceAddressBytes( const char *address, uint8_t*uit) {
    sscanf(address,"%hhX:%hhX:%hhX:%hhX:%hhX:%hhX",uit,uit+1,uit+2,uit+3,uit+4,uit+5);
    }
extern "C" JNIEXPORT jint  JNICALL   fromjava(GlucoseMeterGetIndex)(JNIEnv *env, jclass cl,jstring jdeviceName,jstring jaddress) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        uint8_t address[8]{};
        bool hasaddress=false;
        if(jaddress) {
            const char *in = env->GetStringUTFChars( jaddress, NULL);
            if(in != nullptr) {
                deviceAddressBytes(in,address);
                env->ReleaseStringUTFChars(jaddress, in);
                hasaddress=true;
                }
            }
        unsigned len= env->GetStringUTFLength(jdeviceName );
        jint jlen = env->GetStringLength( jdeviceName);
        char buf[len+1];
        env->GetStringUTFRegion(jdeviceName, 0,jlen, buf);
        buf[len]='\0';
        std::string_view deviceName{buf,len};
        if(const GlucoseMeter *meter= settings->data()->giveGlucoseMeter(deviceName,hasaddress?address:nullptr)) {
                int meterIndex=meter-settings->data()->glucosemeters;
                LOGGER("GlucoseMeterGetIndex(%s,%lX)=%d hasaddress=%d\n",buf,*(uint64_t*)address,meterIndex,hasaddress)
                return meterIndex;
                }

        LOGGER("GlucoseMeterGetIndex(%s,%lX)=-1 hasaddress=%d\n",buf,*(uint64_t*)address,hasaddress)
        return -1;
        }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterRemoveIndex)(JNIEnv *env, jclass cl,jint index) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        return settings->data()->removeGlucoseMeter(index);
        }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterRemove)(JNIEnv *env, jclass cl,jstring jdeviceName,jstring jaddress) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        uint8_t address[6];
        bool hasaddress=false;
        if(jaddress) {
            const char *in = env->GetStringUTFChars( jaddress, NULL);
            if(in != nullptr) {
                deviceAddressBytes(in,address);
                env->ReleaseStringUTFChars(jaddress, in);
                hasaddress=true;
                }
            }
        unsigned len= env->GetStringUTFLength(jdeviceName );
        jint jlen = env->GetStringLength( jdeviceName);
        char buf[len+1];
        env->GetStringUTFRegion(jdeviceName, 0,jlen, buf);
        buf[len]='\0';
        std::string_view deviceName{buf,len};
        return settings->data()->removeGlucoseMeter(deviceName,hasaddress?address:nullptr);
        }
extern "C" JNIEXPORT jint  JNICALL   fromjava(GlucoseMeterHasIndex)(JNIEnv *env, jclass cl,jstring jdeviceName,jstring jaddress) {
        uint8_t address[8]{};
        bool hasaddress=false;
        if(jaddress) {
            const char *in = env->GetStringUTFChars( jaddress, NULL);
            if(in != nullptr) {
                deviceAddressBytes(in,address);
                env->ReleaseStringUTFChars(jaddress, in);
                hasaddress=true;
                }
            }
        unsigned len= env->GetStringUTFLength(jdeviceName );
        jint jlen = env->GetStringLength( jdeviceName);
        char buf[len+1];
        env->GetStringUTFRegion(jdeviceName, 0,jlen, buf);
        buf[len]='\0';
        std::string_view deviceName{buf,len};
        if(const GlucoseMeter *meter= settings->data()-> getGlucoseMeter(deviceName,hasaddress?address:nullptr)) {
                int meterIndex=meter-settings->data()->glucosemeters;
                LOGGER("GlucoseMeterHasIndex(%s,%lX)=%d hasaddress=%d\n",buf,*(uint64_t*)address,meterIndex,hasaddress)
                return meterIndex;
                }
        LOGGER("GlucoseMeterHasIndex(%s,%lX)=-1 hasaddress=%d\n",buf,*(uint64_t*)address,hasaddress)
        return -1;
        }

static bool isAidexX(const char *name) {
    const std::string_view names[]{"AiDEX X-"sv,"LinX-"sv,"Lumi-"sv};
    for(auto &n:names) {
        if(!memcmp(name,n.data(),n.size()))
            return true;
        }
    return false;
    }
#if 0 
extern "C" JNIEXPORT jintArray  JNICALL   fromjava(getActiveGlucoseMeters)(JNIEnv *env, jclass cl) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        const uint32_t nr=settings->data()->glucoseMeterNR;
        jint meters[nr];
        int used=0;
        for(int i=0;i<nr;i++) {
            if(settings->data()->glucosemeters[i].active) {
                meters[used++]=i;
                }
            }
	jintArray  uit=env->NewIntArray(used) ;
	env->SetIntArrayRegion(uit, 0,used, meters);
	return uit;
	}
#else
extern "C" JNIEXPORT jintArray  JNICALL   fromjava(getActiveGlucoseMeters)(JNIEnv *env, jclass cl) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
    const uint32_t nr=settings->data()->glucoseMeterNR;
    LOGGER("getActiveGlucoseMeters nr=%d\n",nr);
    jint meters[nr];
    int used=0;
    for(int i=0;i<settings->data()->glucoseMeterNR;) {
        GlucoseMeter &meter= settings->data()->glucosemeters[i];
        if(isAidexX(meter.deviceName)) {
            LOGGER("getActiveGlucoseMeters %s is AidexX()\n",meter.deviceName);
            const int next=i+1;
            const int left= settings->data()->glucoseMeterNR-next;
            if(left>0)
                memmove(settings->data()->glucosemeters+i,settings->data()->glucosemeters+next,left*sizeof(GlucoseMeter));
            --settings->data()->glucoseMeterNR;
            continue;
            }
        if(meter.active) {
            meters[used++]=i;
            }
        ++i;
        }
	jintArray  uit=env->NewIntArray(used) ;
	env->SetIntArrayRegion(uit, 0,used, meters);
	return uit;
	}
#endif
extern "C" JNIEXPORT jstring  JNICALL   fromjava(GlucoseMeterDeviceName)(JNIEnv *env, jclass cl,jint meterIndex) {
        if(GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                return env->NewStringUTF(meter->deviceName);
                }
         return nullptr;
         }
extern "C" JNIEXPORT jlong  JNICALL   fromjava(GlucoseMeterGetLastTime)(JNIEnv *env, jclass cl,jint meterIndex) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        if(const GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                #ifndef NOLOG
                time_t tim= meter->lastTime;
                LOGGER("GlucoseMeterGetLastTime %u %s",meter->lastTime,ctime(&tim));
                #endif
                return meter->lastTime*1000LL;
                }
         return 0LL;
         }
extern "C" JNIEXPORT void  JNICALL   fromjava(GlucoseMeterSetLastTime)(JNIEnv *env, jclass cl,jint meterIndex,jlong lasttime) {
    std::lock_guard<std::mutex> lock(meterImportMutex);
        if(GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                 meter->nextIndex=0;
                 meter->active=true;
                 meter->lastTime=lasttime/1000;
                #ifndef NOLOG
                time_t tim= meter->lastTime;
                LOGGER("GlucoseMeterSetLastTime %u %s",meter->lastTime,ctime(&tim));
                #endif
                }
         }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterSetActive)(JNIEnv *env, jclass cl,jint meterIndex,jboolean active) {
        if(GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
               if(meter->active!=active) {
                    meter->active=active;
                    return true;
                    }
                }
         return false;
         }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterGetActive)(JNIEnv *env, jclass cl,jint meterIndex) {
        if(const GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                return meter->active;
                }
         return false;
         }
#include <format>
std::string deviceAddressString(const uint8_t *address) {
        return std::format("{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
            address[0],
            address[1],
            address[2],
            address[3],
            address[4],
            address[5]);
        }
extern "C" JNIEXPORT jstring  JNICALL   fromjava(GlucoseMeterDeviceAddress)(JNIEnv *env, jclass cl,jint meterIndex) {
        if(const GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                const uint8_t *address=meter->deviceAddress;
                if(*reinterpret_cast<const int32_t*>(address)||*reinterpret_cast<const int16_t*>(address+4))
                        return env->NewStringUTF(deviceAddressString(address).data());
                }
         return nullptr;
         }
extern "C" JNIEXPORT jboolean  JNICALL   fromjava(GlucoseMeterSetDeviceAddress)(JNIEnv *env, jclass cl,jint meterIndex,jstring jaddress) {
        if(GlucoseMeter *meter= settings->data()->getGlucoseMeter(meterIndex)) {
                if(jaddress) {
                    const char *in = env->GetStringUTFChars( jaddress, NULL);
                    if(in != nullptr) {
                        uint8_t *uit=meter->deviceAddress;
                        deviceAddressBytes(in,uit);
                        env->ReleaseStringUTFChars(jaddress, in);
                        return true;
                        }
                    }
                uint8_t *address=meter->deviceAddress;
                std::fill_n(address,6,0);
                return true;
                }
          return false;
         }


extern "C" JNIEXPORT jint  JNICALL   fromjava(GlucoseMeterCount)(JNIEnv *env, jclass cl) {
        return settings->data()->glucoseMeterNR;
         }

extern bool hasGlucoseMeters();
 bool hasGlucoseMeters() {
        const int nr=settings->data()->glucoseMeterNR;
        for(int i=0;i<nr;++i) {
            if(settings->data()->glucosemeters[i].active) {
                return true; 
                }
            }
         return false;
        }
