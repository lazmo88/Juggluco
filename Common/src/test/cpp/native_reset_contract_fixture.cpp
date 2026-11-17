// SPDX-License-Identifier: GPL-3.0-or-later
// Harness dependencies only are synthetic; Numdata and JNI bodies are extracted
// verbatim at build time. Rows/header live in an actual MAP_SHARED fsynced file.
#include <array>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <functional>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <sys/wait.h>
#include "nums/num.h"
#include "nums/healthsource.hpp"
#include "nums/healthblood.hpp"
#include "nums/mirrorrepair.hpp"
#include "nums/mirrordatabase.hpp"
#include "net/mirrorvalidate.hpp"
#define LOGGERTAG(...) ((void)0)
#define LOGARTAG(...) ((void)0)
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
using identtype=long long;
using crypt_t=int;
struct pathconcat { pathconcat(){} pathconcat(const pathconcat&,const char*){} };
pathconcat numbasedir;
struct Settings { int getlabelcount()const{return 5;} } settingsObject;
auto settings=&settingsObject;
struct Backup { void wakebackup(int){} };
Backup *backup=nullptr;
constexpr int wakenums=1;
std::mutex nummutexupdate;
#define NUMLOCKGUARD std::lock_guard<std::mutex> updateLock(nummutexupdate);
struct numspan {int start=0,end=0;};
struct changednums {int len=1,lastlastpos=0;numspan changed[100];};
struct Connect {
    int remote=0;std::vector<std::vector<uint8_t>> sent;
    bool s_noacksendcommand(crypt_t*,const uint8_t*,size_t){return true;}
    std::unique_ptr<uint8_t[]> receivedata_s(crypt_t*,size_t n){auto p=std::make_unique<uint8_t[]>(n);memcpy(p.get(),&remote,n);return p;}
    bool sendcommand(crypt_t*,const uint8_t *p,size_t n){sent.emplace_back(p,p+n);return true;}
};
bool makerandom(void *p,size_t n){uint64_t words[]{111,222};assert(n==16);memcpy(p,words,n);return true;}
int flushes=0,failFlush=0;
std::vector<size_t> lengths;
std::function<void(void*,size_t)> onFlush;
int testMsync(void *p,size_t n,int flags){
    ++flushes;lengths.push_back(n);
    if(onFlush)onFlush(p,n);
    if(flushes==failFlush){errno=EIO;return -1;}
    return ::msync(p,n,flags);
}
void resetFlush(){flushes=failFlush=0;lengths.clear();onFlush={};}
#define msync testMsync
struct Mapping {
    int fd;Num *p;static constexpr size_t bytes=8192;
    explicit Mapping(const std::string &file):fd(open(file.c_str(),O_RDWR|O_CREAT,0600)){
        assert(fd>=0&&ftruncate(fd,bytes)==0);
        p=static_cast<Num*>(mmap(nullptr,bytes,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0));assert(p!=MAP_FAILED);
    }
    ~Mapping(){assert(fsync(fd)==0);munmap(p,bytes);close(fd);}
    Num *data(){return p;} const Num *data()const{return p;}
    size_t size()const{return bytes/sizeof(Num);}
};
struct Numdata {
    Mapping nums;std::mutex nummutex;identtype ident=-1;
    bool identityOK=true;int changed=0,one=0;bool received=false;
    bool updatebusy[2]{};
    static constexpr int firstpos=144,lastpos=128,lastpolledpos=120,newidentpos=32;
    static constexpr int intinnum=sizeof(Num)/sizeof(uint32_t);
    explicit Numdata(const std::string &file):nums(file){}

    bool mappingsAvailable()const{return true;}size_t capacity()const{return nums.size()-16;}
    bool writeident(pathconcat,identtype){return identityOK;}

    int &getchangedpos(){return changed;}int &getonechange(){return one;}
    void updatepos(int,int){} void setchangetimes(int,int){}
    void setonlychangetimes(int,int,const Num*){}void addmorelibrenumschanged(int,int,const Num*){}
    void setlastchange(int){}void nightBack(int){}void updateposnowake(int,int){}
    bool &receivedbackup(){return received;}void librechangelastpos(int){}
    template<int nr,typename T>struct ardeleter {void operator()(T *ptr){operator delete[](ptr,std::align_val_t(nr));}};
// NUMDATA_BODIES
};
#define JNIEXPORT
#define JNICALL
#define fromjava(name) name
using JNIEnv=void;using jclass=void*;using jlong=long long;using jint=int;
// JNI_BODIES
#undef msync
static uint32_t word(const Numdata &d,size_t off){uint32_t v;memcpy(&v,d.raw()+off,4);return v;}
static void put(Numdata &d,size_t off,uint32_t v){memcpy(d.raw()+off,&v,4);}
static void seed(Numdata &d,identtype id=-1){
    memset(d.raw(),0,Mapping::bytes);d.setnewident(id);d.setlastpos(2);
    d.at(0)={.time=1700000000,.mealptr=0,.value=6,.type=3};
    d.at(1)={.time=1700000001,.mealptr=0,.value=7,.type=3};
    healthblood::SourceToken t;assert(d.healthInventoryToken(t));assert(fsync(d.nums.fd)==0);resetFlush();
}
static std::vector<uint32_t> packet(uint32_t start,uint32_t count,uint32_t last){
    std::vector<uint32_t> bytes((sizeof(numsend)+8+count*sizeof(Num))/4);
    auto *p=reinterpret_cast<numsend*>(bytes.data());p->type=snums;p->dbase=1;p->nr=1;p->totlen=bytes.size()*4;p->last=last;
    p->nums[0]=start;p->nums[1]=count;
    for(uint32_t i=0;i<count;++i){Num row{.time=1700000000+i,.mealptr=0,.value=float(8+i),.type=3};memcpy(p->nums+2+i*4,&row,sizeof(row));}
    return bytes;
}
static bool snapshot(Numdata &here,Numdata &mirror){std::vector<Numdata*> stores{&here,&mirror};std::vector<int64_t> rows;return healthBloodSnapshot(stores,1,3,rows);}
static void saveSnapshot(const std::string &dir,const char *name,Numdata &here,Numdata &mirror){
    std::vector<Numdata*> stores{&here,&mirror};std::vector<int64_t> rows;std::ofstream file(dir+"/"+name);
    if(!healthBloodSnapshot(stores,1,3,rows)){file<<"null\n";return;}
    for(auto value:rows)file<<value<<' ';
    file<<'\n';
}
static void resetTest(const std::string &base,const std::string &snapshots){
    const std::string file=base+"/reset";Numdata here(base+"/local");seed(here,0);here.setlastpos(0);
    {Numdata d(file);seed(d);assert(snapshot(here,d));
        // Reproduce constructor's fresh mirror member ident=-1, raw newident=0.
        memset(d.raw()+32,0,sizeof(identtype));assert(d.getnewident()==here.getnewident());
        saveSnapshot(snapshots,"before.txt",here,d);
        numinit init{.first=0,.ident=-1};assert(d.numbackupinit(&init));
        saveSnapshot(snapshots,"mid.txt",here,d);
        assert(!snapshot(here,d) && "unfinished initialization must not expose retained rows in a complete new epoch");
        assert(d.at(0).value==6 && d.getlastpos()==2);assert(word(d,232)!=0);
        Connect c;changednums changes[2];assert(d.update(nullptr,&c,changes,0)==2&&c.sent.empty());
    }
    {Numdata d(file);assert(d.mirrorRepairPending()&&!snapshot(here,d));
        auto part=packet(1,1,2);assert(!d.backupnums(reinterpret_cast<numsend*>(part.data())));assert(d.at(0).value==6);
        auto full=packet(0,2,2);assert(d.backupnums(reinterpret_cast<numsend*>(full.data())));
        assert(!d.mirrorRepairPending()&&snapshot(here,d));assert(d.at(0).value==8);
        saveSnapshot(snapshots,"after.txt",here,d);
        assert(lengths.size()>=2&&lengths[lengths.size()-2]>=256+2*sizeof(Num)&&lengths.back()==256);
    }
    {Numdata d(file);assert(!d.mirrorRepairPending()&&snapshot(here,d)&&d.at(0).value==8);}
    puts("PASS durable interrupted init, retained app rows, blocked HC snapshot/outbound, partial refusal/full replacement/reopen");
}
static void repairTest(const std::string &base){
    const std::string dir=base+"/repair";std::filesystem::create_directory(dir);
    const std::string file=dir+"/nums-1";
    healthblood::SourceToken before,after;
    {Numdata d(file);seed(d);assert(d.healthInventoryToken(before));
        const unsigned char magic[]={0xfe,0xd2,0xde,0xad};memcpy(d.raw(),magic,4);d.raw()[16]=1;
        put(d,120,1700000000);put(d,128,1700000000);assert(fsync(d.nums.fd)==0);
    }
    // A trim error stops startup after its initial durable marker/scope write.
    std::filesystem::create_directory(dir+"/libreids.dat");
    bool failed=false;try{numrepair::prepare(dir,file.c_str(),128);}catch(const std::runtime_error&){failed=true;}
    assert(failed);
    {Numdata d(file);assert(d.healthInventoryToken(after));
        assert(after.epoch>before.epoch && "startup must persist new scope BEFORE auxiliary trim or coverage rewrite");
        assert(d.mirrorRepairPending()&&d.getlastpos()==1700000000);
        // Simulate startup after an interrupted numbackupinit as well.
        put(d,232,healthblood::resetPending);assert(fsync(d.nums.fd)==0);
    }
    std::filesystem::remove(dir+"/libreids.dat");
    numrepair::prepare(dir,file.c_str(),128);
    {Numdata d(file);assert(d.getlastpos()==2&&d.getlastpolledpos()==2&&word(d,20)==0);
        assert(word(d,232)==healthblood::resetPending&&d.mirrorRepairPending());
    }
    // Ordinary historical repair has a new scope and complete usable coverage.
    {Numdata d(file);put(d,232,0);put(d,120,1700000000);put(d,128,1700000000);assert(d.healthInventoryToken(before));assert(fsync(d.nums.fd)==0);}
    numrepair::prepare(dir,file.c_str(),128);
    {Numdata d(file);assert(d.healthInventoryToken(after));assert(after.epoch>before.epoch&&!d.mirrorRepairPending());assert(d.getlastpos()==2);}
    // A legacy mirror without metadata gets a real durable random UUID before
    // its first repaired coverage is installed, with no Android dependencies.
    {Numdata d(file);memset(d.raw()+200,0,32);put(d,120,1700000000);put(d,128,1700000000);assert(fsync(d.nums.fd)==0);}
    numrepair::prepare(dir,file.c_str(),128);
    {Numdata d(file);assert(word(d,200)==healthblood::metadataMagic);assert(d.healthInventoryToken(after));assert(after.epoch==1&&(after.hi||after.lo));assert(!d.mirrorRepairPending());}
    puts("PASS real startup repair: new durable scope precedes failed trim, restart corrects coverage and preserves separate HC pending");
}
static void failClosedTest(const std::string &base){
    Numdata here(base+"/failure-local");seed(here,0);here.setlastpos(0);
    const std::string file=base+"/failures";
    // Failed initial durable write cannot touch logical positions or scope.
    {Numdata d(file);seed(d);std::array<unsigned char,256> before;memcpy(before.data(),d.raw(),256);
        numinit init{.first=1,.ident=-1};failFlush=1;
        assert(!d.numbackupinit(&init));assert(!memcmp(before.data(),d.raw(),256));
        assert(d.at(0).value==6&&snapshot(here,d));resetFlush();
        // At the actual flush boundary old coverage is intact, new marker and
        // epoch are already present in that very SAME write.
        onFlush=[&](void *p,size_t n){assert(n==256&&p==d.nums.data());assert(word(d,232)==healthblood::resetPending);assert(d.getfirstpos()==0&&d.getlastpos()==2);assert(word(d,224)==2);};
        assert(d.numbackupinit(&init));resetFlush();assert(!snapshot(here,d));
    }
    // Identifier write failure after the marker flush is safely latched.
    {Numdata d(file);seed(d);d.identityOK=false;numinit init{.first=0,.ident=99};
        assert(!d.numbackupinit(&init)&&word(d,232)==healthblood::resetPending&&!snapshot(here,d));
    }
    for(int fail=2;fail<=3;++fail){
        {Numdata d(file);seed(d);numinit init{.first=0,.ident=-1};assert(d.numbackupinit(&init));resetFlush();
            failFlush=fail;auto full=packet(0,2,2);
            assert(!d.backupnums(reinterpret_cast<numsend*>(full.data())));
            assert(word(d,232)==healthblood::resetPending&&!snapshot(here,d));resetFlush();
        }
        {Numdata d(file);assert(d.mirrorRepairPending()&&!snapshot(here,d));auto full=packet(0,2,2);assert(d.backupnums(reinterpret_cast<numsend*>(full.data())));}
    }
    for(int fail=2;fail<=3;++fail){
        {Numdata d(file);seed(d);numinit init{.first=0,.ident=-1};assert(d.numbackupinit(&init));resetFlush();failFlush=fail;
            assert(!d.receivelastpos(1,0));assert(d.mirrorRepairPending()&&!snapshot(here,d));resetFlush();
        }
        {Numdata d(file);assert(d.mirrorRepairPending());assert(d.receivelastpos(1,0));assert(!d.mirrorRepairPending()&&d.getlastpos()==0&&snapshot(here,d));}
    }
    // Unknown markers remain blocked and can never be cleared as a known reset.
    for(size_t off:{size_t(20),size_t(232)}){Numdata d(file);seed(d);put(d,off,0x12345678);assert(d.mirrorRepairPending());assert(!d.receivelastpos(1,0));assert(word(d,off)==0x12345678);auto full=packet(0,2,2);assert(!d.backupnums(reinterpret_cast<numsend*>(full.data())));assert(word(d,off)==0x12345678&&!snapshot(here,d));resetFlush();}
    // Abrupt exit, no Numdata/Mapping destructors or final fsync: beginReset's
    // own header msync must suffice to keep restart blocked.
    {Numdata d(file);seed(d);}
    const auto child=fork();assert(child>=0);
    if(!child){Numdata d(file);numinit init{.first=0,.ident=-1};assert(d.numbackupinit(&init));_exit(0);}
    int status;assert(waitpid(child,&status,0)==child&&WIFEXITED(status)&&WEXITSTATUS(status)==0);
    {Numdata d(file);assert(d.mirrorRepairPending()&&!snapshot(here,d));}
    // Every actual Wear/raw setter leaves a reset latched, even at first=last=0.
    {Numdata d(file);seed(d);numinit init{.first=0,.ident=-1};assert(d.numbackupinit(&init));auto p=reinterpret_cast<jlong>(&d);
        setident(nullptr,nullptr,p,-1);setfirstNum(nullptr,nullptr,p,0);setlastNum(nullptr,nullptr,p,0);setlastpolledNum(nullptr,nullptr,p,0);updatedNum(nullptr,nullptr,p,0);updatedNumstartend(nullptr,nullptr,p,0,0);
        assert(d.mirrorRepairPending()&&!snapshot(here,d));assert(d.receivelastpos(1,0));assert(snapshot(here,d));
    }
    puts("PASS failure-before-mutation, identifier failure latch, prefix/header msync failures with reopen and checked ACKs, unknown markers, Wear setters, empty completion");
}
static void senderTest(const std::string &base){
    Numdata d(base+"/sender");seed(d);d.setlastpos(4);d.at(2)=d.at(0);d.at(3)=d.at(1);
    Connect c;c.remote=4;changednums changes[2];auto &ch=changes[d.getindex()];ch.len=2;ch.lastlastpos=4;ch.changed[0]={0,2};ch.changed[1]={2,4};
    assert(d.update(nullptr,&c,changes,0)==1&&c.sent.size()==1);
    auto *p=reinterpret_cast<const numsend*>(c.sent[0].data());
    assert(p->nr==2&&p->last==4&&p->totlen==c.sent[0].size());assert(mirrorvalidate::numbers(*p,p->totlen,d.capacity(),0,0));
    // No 256-byte metadata header is sent: spans contain exactly the row bytes.
    auto *bytes=reinterpret_cast<const unsigned char*>(p->nums);size_t off=0;
    for(int i=0;i<2;++i){uint32_t start,count;memcpy(&start,bytes+off,4);memcpy(&count,bytes+off+4,4);off+=8;assert(!memcmp(bytes+off,d.startdata()+start,count*sizeof(Num)));off+=count*sizeof(Num);}
    assert(sizeof(numsend)+off==c.sent[0].size());
    c.sent.clear();c.remote=0;d.setlastpos(0);
    assert(d.update(nullptr,&c,changes,0)==1&&c.sent.size()==2);
    auto *last=reinterpret_cast<const lastpos_t*>(c.sent.back().data());assert(last->lastpos==0);
    puts("PASS actual Numdata::update emits one all-span packet with last=end, row-only payload from +256, confirmed-empty lastpos");
}
static void jniTest(const std::string &base){
    for(identtype id:{-1,0})for(int op=0;op<6;++op){
        Numdata d(base+"/jni");seed(d,id);d.setlastpos(4);
        healthblood::SourceToken before,after;assert(d.healthInventoryToken(before));
        auto p=reinterpret_cast<jlong>(&d);
        if(op==0)setlastNum(nullptr,nullptr,p,2);
        if(op==1)setlastpolledNum(nullptr,nullptr,p,2);
        if(op==2)updatedNum(nullptr,nullptr,p,2);
        if(op==3)updatedNumstartend(nullptr,nullptr,p,0,2);
        if(op==4)setfirstNum(nullptr,nullptr,p,2);
        if(op==5)setident(nullptr,nullptr,p,id==0?9:10);
        assert(d.healthInventoryToken(after));
        const bool advance=op==0||op==4||op==5||id!=0;
        assert(after.epoch==before.epoch+(advance?1:0));
        assert(d.getlastpos()==((op==0||((op==1||op==2||op==3)&&id!=0))?2:4));
    }
    puts("PASS all six actual raw JNI setter bodies: local/mirror truncation scopes and unchanged local poll-only coverage");
}
int main(int argc,char **argv){assert(argc>=2);std::string base=argv[1];resetTest(base,argc>2?argv[2]:base);repairTest(base);failClosedTest(base);senderTest(base);jniTest(base);}
