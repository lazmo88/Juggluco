// Synthetic rows only. JNI and insertion bodies are copied verbatim by runner.
#include <algorithm>
#include <atomic>
#include <thread>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>
#include <memory>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "nums/num.h"
#include "nums/libreids.h"
#include "settings/GlucoseMeter.hpp"
#include "meter/MeterData.hpp"
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
#define LOGGERTAG(...) ((void)0)
#define NOLOG
#define JNIEXPORT
#define JNICALL
#define fromjava(name) name
using jint=int;using jlong=long long;using jclass=void*;using jboolean=bool;using jbyte=int8_t;
using jbyteArray=std::vector<uint8_t>*;
struct JNIEnv {
    std::vector<std::unique_ptr<std::vector<uint8_t>>> arrays;
    jbyteArray NewByteArray(int n){arrays.emplace_back(new std::vector<uint8_t>(n));return arrays.back().get();}
    void SetByteArrayRegion(jbyteArray a,int start,int n,const jbyte *in){assert(size_t(start+n)<=a->size());memcpy(a->data()+start,in,n);}
    int GetArrayLength(jbyteArray a){return a->size();}
    void GetByteArrayRegion(jbyteArray a,int start,int n,jbyte *out){assert(size_t(start+n)<=a->size());memcpy(out,a->data()+start,n);}
    bool ExceptionCheck(){return false;}
};
int criticalArrays=0;
struct CritAr {jbyteArray a;CritAr(JNIEnv*,jbyteArray v):a(v){++criticalArrays;}~CritAr(){--criticalArrays;} const void *data()const{return a->data();}};
// UNIT_CONSTANTS
struct Settings {
    int bloodvar=3,unit=2;bool frozen=false;GlucoseMeter meter{.deviceName="Synthetic"};
    Settings* data(){return this;}bool staticnum(){return frozen;}
    GlucoseMeter *getGlucoseMeter(int i){return i==0?&meter:nullptr;}
    int labelCount=8;int getlabelcount(){return labelCount;}
    std::string label="synthetic";std::string getlabel(int){return label;}
} object;
auto *settings=&object;
struct Backup {void wakebackup(int){}};Backup *backup=nullptr;constexpr int wakenums=1;
std::mutex meterImportMutex;
struct Meal {Meal *datameal(){return this;}uint32_t endmeal(uint32_t m){return m;}} meal;
auto *meals=&meal;
int calibrations=0,changed=0;
struct Numdata;
void addCalibration(uint32_t,int,Num*,const Numdata*){++calibrations;}
void setnumchanged(uint32_t){++changed;}
int flushes=0,failFlush=0;
int testMsync(void *p,size_t n,int f){assert(criticalArrays==0&&"release JNI critical array before blocking persistence");++flushes;if(flushes==failFlush){errno=EIO;return -1;}return ::msync(p,n,f);}
#define msync testMsync
template<class T>struct Mapping {
    int fd;T *p;static constexpr size_t bytes=16384;
    explicit Mapping(const std::string &file):fd(open(file.c_str(),O_CREAT|O_RDWR,0600)){
        assert(fd>=0&&ftruncate(fd,bytes)==0);p=static_cast<T*>(mmap(nullptr,bytes,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0));assert(p!=MAP_FAILED);
    }
    ~Mapping(){munmap(p,bytes);close(fd);}
    T *data(){return p;}const T *data()const{return p;}size_t size()const{return bytes/sizeof(T);}
    T &operator[](size_t n){assert(n<size());return p[n];}
};
struct Numdata {
    Mapping<Num> nums;Mapping<Libreids> libreids;Mapping<int32_t> librechanged;Mapping<uint32_t> changetimes;std::mutex nummutex;
    static constexpr int firstpos=144,lastpos=128,lastpolledpos=120,libresend=160,librechangednr=164;static constexpr uint32_t mindistancesecs=90;
    explicit Numdata(const std::string &file):nums(file),libreids(file+".ids"),librechanged(file+".changes"),changetimes(file+".times"){}
    bool mirrorRepairPending()const{return false;}
    // Only external upload observer notifications are shims. Header offsets,
    // insertion, dedupe, row movement, change times and Libre IDs use real bodies.
    void setlastchange(Num*){}void nightBack(int){}void updateposnowake(int,int){}
// NUMDATA_BODIES
};
Numdata *database=nullptr;
Numdata *getherenums(){return database;}
// JNI_BODIES
#undef msync
static std::vector<uint8_t> record(uint16_t seq,int hour,int minute,int offset=0,uint16_t value=0xb064){
    MeterData d{};d.typeAndLocationPresent=true;d.index=static_cast<int16_t>(seq);
    d.time={.year=2024,.month=1,.day=2,.hour=static_cast<int8_t>(hour),.minute=static_cast<int8_t>(minute),.second=0};
    d.timeOffsetPresent=offset!=0;
    if(offset){d.offset=offset;d.rest1.glucose.whole=value;}else d.rest2.glucose.whole=value;
    std::vector<uint8_t> out(sizeof(d));memcpy(out.data(),&d,sizeof(d));return out;
}
static MeterImportReceipt accepted(JNIEnv &env,std::vector<uint8_t> &r){
#ifdef HAS_RECEIPTS
    // New explicit-admission storage cases. Replay/context cases below pass a
    // previously persisted binding directly; never reprepare for a real replay.
    auto binding=GlucoseMeterPrepareAccepted(&env,nullptr,0,&r);
    return static_cast<MeterImportReceipt>(GlucoseMeterImportAccepted(&env,nullptr,0,&r,binding));
#else
    GlucoseMeterSave(&env,nullptr,0,&r);return MeterImportReceipt::Persisted; // old process unconditionally completed
#endif
}
int main(int argc,char **argv){
    assert(argc==2||argc==3);JNIEnv env;const std::string file=std::string(argv[1])+"/nums0";
#ifdef HAS_RECEIPTS
    if(argc==3&&std::string(argv[2]).starts_with("--context-")) {
        const std::string mode=argv[2];const int failure=mode.back()-'0';
        assert(failure>=0&&failure<=4);
        const std::string path=std::string(argv[1])+"/context"+std::to_string(failure);
        const std::string boundPath=path+".binding";auto raw=record(65535,12,0);
        if(mode.starts_with("--context-seed-")) {
            auto binding=GlucoseMeterPrepareAccepted(&env,nullptr,0,&raw);assert(binding);
            // Synthetic stand-in for the synchronous preference commit; the
            // Java production state transition/codec is exercised separately.
            int fd=open(boundPath.c_str(),O_CREAT|O_TRUNC|O_WRONLY,0600);assert(fd>=0);
            assert(write(fd,binding->data(),binding->size())==ssize_t(binding->size())&&fsync(fd)==0&&close(fd)==0);
            Numdata d(path);database=&d;failFlush=failure;
            assert(d.getlastpos()==0&&flushes==0&&settings->meter.lastTime==0);
            const int receipt=GlucoseMeterImportAccepted(&env,nullptr,0,&raw,binding);
            assert(receipt==(failure?0:1)&&d.getlastpos()==1);
            if(failure)assert(settings->meter.lastTime==0);
        }
        else {
            assert(mode.starts_with("--context-replay-"));
            int fd=open(boundPath.c_str(),O_RDONLY);struct stat stat{};assert(fd>=0&&fstat(fd,&stat)==0);
            assert(stat.st_size>=104&&stat.st_size<=154);std::vector<uint8_t> binding(stat.st_size);
            assert(read(fd,binding.data(),binding.size())==ssize_t(binding.size())&&close(fd)==0);
            Numdata d(path);database=&d;settings->meter.timeoffset=1;
            assert(GlucoseMeterImportAccepted(&env,nullptr,0,&raw,&binding)==2&&d.getlastpos()==1);
            settings->meter.timeoffset=3601;
            assert(GlucoseMeterImportAccepted(&env,nullptr,0,&raw,&binding)==2&&d.getlastpos()==1);
            assert(settings->meter.nextIndex==0);
            printf("PASS separate-process persisted binding replay after %s%d, offset +1sec then +3601sec, rows=1\n",failure?"uncertain flush ":"completed import ",failure);
        }
        return 0;
    }
    {
        Numdata d(std::string(argv[1])+"/binding-validation");database=&d;
        auto raw=record(42,12,0);auto binding=GlucoseMeterPrepareAccepted(&env,nullptr,0,&raw);
        assert(binding&&d.getlastpos()==0&&flushes==0&&settings->meter.lastTime==0&&changed==0);
        auto apply=[&](std::vector<uint8_t> &b){return GlucoseMeterImportAccepted(&env,nullptr,0,&raw,&b);};
        settings->unit=1;assert(apply(*binding)==0);settings->unit=2;
        settings->bloodvar=4;assert(apply(*binding)==0);settings->bloodvar=3;
        settings->label="renamed";assert(apply(*binding)==0);settings->label="synthetic";
        settings->labelCount=3;assert(apply(*binding)==0);settings->labelCount=8;
        settings->meter.deviceName[0]='X';assert(apply(*binding)==0);settings->meter.deviceName[0]='S';
        settings->meter.deviceAddress[0]=1;assert(apply(*binding)==0);settings->meter.deviceAddress[0]=0;
        for(size_t at=0;at<binding->size();++at) {
            auto corrupt=*binding;corrupt[at]^=1;assert(apply(corrupt)==0);
        }
        // Recomputed checksums do not make incompatible tuple/raw fields valid.
        for(int at:{4,8,12,16,20,24,28,73,85,86,96}) {
            auto corrupt=*binding;corrupt[at]^=1;
            meterbinding::put(corrupt.data()+corrupt.size()-4,meterbinding::checksum(corrupt.data(),corrupt.size()-4));
            assert(apply(corrupt)==0);
        }
        auto wrong=raw;wrong[1]++;assert(GlucoseMeterImportAccepted(&env,nullptr,0,&wrong,binding)==0);
        wrong=raw;wrong[10]++;assert(GlucoseMeterImportAccepted(&env,nullptr,0,&wrong,binding)==0);
        auto shortBinding=*binding;shortBinding.pop_back();assert(apply(shortBinding)==0);
        assert(d.getlastpos()==0&&flushes==0&&changed==0);
        settings->meter.timeoffset=1;
        assert(apply(*binding)==1&&d.getlastpos()==1);
        assert(apply(*binding)==2&&d.getlastpos()==1);
        settings->meter={.deviceName="Synthetic"};flushes=changed=0;
        puts("PASS read-only preparation; current unit/blood/label/device drift and corrupt/mismatched binding fail closed; frozen offset used");
    }
    if(argc==3&&std::string(argv[2])=="--probe-context-drift") {
        auto raw=record(1,12,0);
        auto binding=GlucoseMeterPrepareAccepted(&env,nullptr,0,&raw);
        assert(binding);
        { Numdata d(file);database=&d;
          assert(d.getlastpos()==0&&flushes==0&&settings->meter.lastTime==0);
          assert(GlucoseMeterImportAccepted(&env,nullptr,0,&raw,binding)==1);
        }
        settings->meter.timeoffset=1;
        Numdata d(file);database=&d;
        const auto receipt=GlucoseMeterImportAccepted(&env,nullptr,0,&raw,binding);
        fprintf(stderr,"CONTEXT DRIFT: replay receipt=%d actual Num rows=%d (same wire identity, meter offset changed +1 second)\n",int(receipt),d.getlastpos());
        assert(receipt==2&&d.getlastpos()==1&&"frozen conversion must prevent context drift duplicates");
        puts("PASS frozen context +1sec replay across actual mapped-store reopen");
        return 0;
    }
#endif
    {
        Numdata d(file);database=&d;auto newer=record(1,12,0),older=record(65535,10,0);
        assert(accepted(env,newer)==MeterImportReceipt::Persisted);const auto cursor=settings->meter;
        assert(accepted(env,older)==MeterImportReceipt::Persisted);
        assert(d.getlastpos()==2 && "explicit older Add silently dropped despite completion receipt/pending cleanup");
        assert(d.begin()[0].time<d.begin()[1].time);
        assert(settings->meter.lastTime==cursor.lastTime&&settings->meter.nextIndex==cursor.nextIndex);
        assert(calibrations==0&&"historical import must never trigger just-now calibration");
        puts("PASS explicit old Add after newer Add persisted; cursor non-regression; no calibration");
        auto legacyOld=record(500,9,0);
        assert(!GlucoseMeterSave(&env,nullptr,0,&legacyOld));
        assert(settings->meter.lastTime==cursor.lastTime&&settings->meter.nextIndex==cursor.nextIndex&&"ordinary GATT old record must not rewind cursor");
#ifdef HAS_RECEIPTS
        // Exact tuple dedupe, including equal-time rows with different values/types.
        assert(accepted(env,older)==MeterImportReceipt::AlreadyPresent&&d.getlastpos()==2);
        d.begin()[0].exclude=true;
        assert(accepted(env,older)==MeterImportReceipt::AlreadyPresent&&d.begin()[0].exclude);
        auto close=older;close[9]=30;
        assert(accepted(env,close)==MeterImportReceipt::Persisted&&d.getlastpos()==3);
        auto different=record(400,10,0,0,0xb078);
        assert(accepted(env,different)==MeterImportReceipt::Persisted&&d.getlastpos()==4);
        settings->bloodvar=4;
        auto typed=record(401,10,0);
        assert(accepted(env,typed)==MeterImportReceipt::Persisted&&d.getlastpos()==5);
        settings->bloodvar=3;
        assert(accepted(env,older)==MeterImportReceipt::AlreadyPresent&&d.getlastpos()==5);
        puts("PASS exact actual-storage tuple dedupe; near timestamp not lost; different value/type retained; exclusion unchanged");

        // Wire minute offsets and signed meter offsets use the actual MeterData decoder.
        auto offset=record(2,9,30,30);
        assert(GlucoseMeterCorrectedTime(&env,nullptr,0,&offset)==GlucoseMeterCorrectedTime(&env,nullptr,0,&older));
        assert(accepted(env,offset)==MeterImportReceipt::AlreadyPresent);
        settings->meter.timeoffset=-180;
        auto offset2=record(3,10,33,-30);
        assert(GlucoseMeterCorrectedTime(&env,nullptr,0,&offset2)==GlucoseMeterCorrectedTime(&env,nullptr,0,&older)+180);
        // Reset the offset before checking the expected actual timestamp: 10:33-30m-3m = 10:00.
        assert(accepted(env,offset2)==MeterImportReceipt::AlreadyPresent);
        settings->meter.timeoffset=0;
        auto wrap=record(65535,13,0),afterWrap=record(0,14,0),outOfOrder=record(500,11,0);
        assert(accepted(env,wrap)==MeterImportReceipt::Persisted&&settings->meter.nextIndex==0);
        assert(accepted(env,afterWrap)==MeterImportReceipt::Persisted&&settings->meter.nextIndex==1);
        auto latest=settings->meter;
        assert(accepted(env,outOfOrder)==MeterImportReceipt::Persisted);
        assert(settings->meter.lastTime==latest.lastTime&&settings->meter.nextIndex==1);
        puts("PASS wire/meter offsets, sequence wrap, out-of-order import and monotone fetch cursor");

        // Every durability boundary can fail. A rejected write must not advance
        // cursor, and exact retry must flush again before returning a receipt.
        for(int failed=1;failed<=4;++failed) {
            auto retry=record(20+failed,15,failed);
            const auto before=settings->meter;const int count=d.getlastpos();
            flushes=0;failFlush=failed;
            assert(accepted(env,retry)==MeterImportReceipt::Rejected);
            assert(settings->meter.lastTime==before.lastTime&&settings->meter.nextIndex==before.nextIndex);
            assert(d.getlastpos()==count+1); // uncertain write: do NOT roll it back
            flushes=0;failFlush=0;
            assert(accepted(env,retry)==MeterImportReceipt::AlreadyPresent&&flushes==4);
            assert(d.getlastpos()==count+1);
        }
        puts("PASS failed auxiliary/primary msync returns rejection, no cursor advance, exact durable retry without duplicate");

        const int count=d.getlastpos();const auto before=settings->meter;
        auto invalid=record(50,16,0,0,0x07ff);
        assert(accepted(env,invalid)==MeterImportReceipt::Rejected);
        invalid=record(51,16,0);invalid[0]&=~2;
        assert(accepted(env,invalid)==MeterImportReceipt::Rejected);
        invalid.resize(8);assert(accepted(env,invalid)==MeterImportReceipt::Rejected);
        auto valid=record(52,16,0);settings->frozen=true;
        assert(accepted(env,valid)==MeterImportReceipt::Rejected);settings->frozen=false;
        settings->bloodvar=maxvarnr;assert(accepted(env,valid)==MeterImportReceipt::Rejected);settings->bloodvar=3;
        settings->unit=0;assert(accepted(env,valid)==MeterImportReceipt::Rejected);settings->unit=2;
        auto binding=GlucoseMeterPrepareAccepted(&env,nullptr,0,&valid);
        assert(GlucoseMeterImportAccepted(&env,nullptr,9,&valid,binding)==0);
        assert(GlucoseMeterImportAccepted(&env,nullptr,0,nullptr,binding)==0);
        assert(GlucoseMeterImportAccepted(&env,nullptr,0,&valid,nullptr)==0);
        database=nullptr;assert(accepted(env,valid)==MeterImportReceipt::Rejected);database=&d;
        assert(d.getlastpos()==count&&settings->meter.lastTime==before.lastTime&&settings->meter.nextIndex==before.nextIndex);
        // Capacity rejection happens before any Num or auxiliary write.
        d.setlastpos(d.capacity());
        assert(accepted(env,valid)==MeterImportReceipt::Rejected);d.setlastpos(count);
        puts("PASS malformed/nonfinite/unsupported units/static store/no label/no meter/no store/full capacity reject without completion");
#endif
    }
#ifdef HAS_RECEIPTS
    {
        Numdata reopened(file);database=&reopened;
        assert(reopened.getlastpos()==12&&"actual mapped rows and count survive close/reopen");
        auto old=record(65535,10,0);
        assert(accepted(env,old)==MeterImportReceipt::AlreadyPresent);
        assert(reopened.getlastpos()==12&&reopened.begin()[0].exclude);
        puts("PASS mapped Num database reopen, actual row dedupe after restart and preserved exclusion");
        // The old boolean still means just-now calibration, not storage success.
        auto legacy=record(70,17,0);
        assert(!GlucoseMeterSave(&env,nullptr,0,&legacy)&&reopened.getlastpos()==13);
        time_t now=time(nullptr);tm t{};localtime_r(&now,&t);
        MeterData fresh{};fresh.typeAndLocationPresent=true;fresh.index=71;
        fresh.time={.year=int16_t(t.tm_year+1900),.month=int8_t(t.tm_mon+1),.day=int8_t(t.tm_mday),.hour=int8_t(t.tm_hour),.minute=int8_t(t.tm_min),.second=int8_t(t.tm_sec)};
        fresh.rest2.glucose.whole=0xb064;std::vector<uint8_t> raw(sizeof(fresh));memcpy(raw.data(),&fresh,sizeof(fresh));
        assert(GlucoseMeterSave(&env,nullptr,0,&raw)&&calibrations==1);
        assert(accepted(env,raw)==MeterImportReceipt::AlreadyPresent&&calibrations==1);
        auto explicitNow=raw;explicitNow[1]++;explicitNow[10]++; // Distinct wire identity/value at same timestamp.
        assert(accepted(env,explicitNow)==MeterImportReceipt::Persisted&&calibrations==1);
        puts("PASS legacy historical boolean false even when saved; just-now boolean true/calibration unchanged; explicit imports never calibrate");
    }
    {
        Numdata concurrent(std::string(argv[1])+"/concurrent");database=&concurrent;settings->meter={.deviceName="Synthetic"};
        std::atomic<int> stored=0,existing=0;
        std::vector<std::thread> threads;
        for(int i=0;i<12;++i) threads.emplace_back([&] {
            // Exercise nummutex without relying on the outer meter JNI mutex.
            const auto receipt=concurrent.importAcceptedMeter(1704196800,100,3);
            if(receipt==MeterImportReceipt::Persisted)++stored;
            else if(receipt==MeterImportReceipt::AlreadyPresent)++existing;
            else assert(false);
        });
        for(auto &thread:threads)thread.join();
        assert(stored==1&&existing==11&&concurrent.getlastpos()==1);
        settings->unit=1;
        auto mmol=record(1,14,0,0,0xc038);mmol[0]|=4; // Explicit wire mol/L unit flag.
        assert(accepted(env,mmol)==MeterImportReceipt::Persisted);
        assert(std::abs(concurrent.end()[-1].value-5.6f)<0.0001f);
        settings->unit=2;
        puts("PASS atomic native nummutex dedupe under 12 writers; explicit wire mmol unit conversion follows production constants");
    }
    {
        const std::string auxFile=std::string(argv[1])+"/aux";
        {
            Numdata d(auxFile);database=&d;settings->meter={.deviceName="Synthetic"};
            auto newer=record(1,12,0),older=record(2,10,0);
            assert(accepted(env,newer)==MeterImportReceipt::Persisted);
            d.libreids[0].nr=12345;d.getlibresend()=1;
            assert(accepted(env,older)==MeterImportReceipt::Persisted);
            assert(d.libreids[1].nr==12345&&d.getlibresend()==2);
            assert(d.getlibrechangednr()==1&&d.librechanged[0]==0);
            assert(d.changetimes[0]>0&&d.changetimes[1]>0);
        }
        Numdata d(auxFile);
        assert(d.libreids[1].nr==12345&&d.getlibresend()==2&&d.getlibrechangednr()==1&&d.librechanged[0]==0);
        puts("PASS real Libre ID movement/change-list/change-time bodies persisted through historical insert and reopen");
    }
#endif
}
