// Included inside Numdata. Never call numsaveonly(): it takes this same mutex.
// Exact dedupe, insertion and durable receipt share nummutex with all Num writers.
MeterImportReceipt importAcceptedMeter(uint32_t tim,float32_t value,uint32_t blood) {
    std::lock_guard<std::mutex> lock(nummutex);
    if(!mappingsAvailable()||mirrorRepairPending()||blood!=settings->data()->bloodvar||
       blood>=maxvarnr||!std::isfinite(value)||value<=0||tim==0)
        return MeterImportReceipt::Rejected;
    const int first=getfirstpos(),last=getlastpos();
    if(first<0||last<first||size_t(last)>capacity())return MeterImportReceipt::Rejected;
    bool present=false;
    // Search actual native rows, including every tuple at the same timestamp.
    // An existing calibration exclusion is never changed/replaced by a retry.
    for(const Num *it=firstAfter(tim);it>begin();) {
        --it;if(it->time!=tim)break;
        if(it->type==blood&&it->value==value){present=true;break;}
    }
    if(!present) {
        if(size_t(last)>=capacity()||size_t(last+1)>libreids.size()||
           size_t(last+1)>changetimes.size()||size_t(last+1)>librechanged.size())
            return MeterImportReceipt::Rejected;
        if(!numsaveonlyLocked(tim,value,blood,0,false,true))return MeterImportReceipt::Rejected;
    }
    // A previous attempt may have inserted but failed a flush. Even AlreadyPresent
    // must re-flush before allowing Java to discard its only retry payload.
    auto sync=[](void *data,size_t bytes) {
        int result;do {result=msync(data,bytes,MS_SYNC);}while(result<0&&errno==EINTR);
        return result==0;
    };
    if(!sync(libreids.data(),libreids.size()*sizeof(*libreids.data()))||
       !sync(librechanged.data(),librechanged.size()*sizeof(*librechanged.data()))||
       !sync(changetimes.data(),changetimes.size()*sizeof(*changetimes.data()))||
       !sync(nums.data(),256+size_t(getlastpos())*sizeof(Num)))
        return MeterImportReceipt::Rejected;
    return present?MeterImportReceipt::AlreadyPresent:MeterImportReceipt::Persisted;
}
