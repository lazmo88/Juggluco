package tk.glucodata;

import java.util.Collection;
import java.util.LinkedHashMap;

/** Android-free state transitions shared by the real meter dialog and host tests. */
class MeterDecisionState<T> {
    /** Metadata contains identities and choices only, never glucose payloads. */
    String reviewText() {
        // Android preference XML can indent after a trailing newline; keep the
        // final character non-whitespace so a restart cannot corrupt metadata.
        StringBuilder out=new StringBuilder("1");
        for(String key:pending.keySet()) {
            Boolean draft=drafts.get(key);
            if(presented.contains(key)||draft!=null)
                out.append('\n').append(key).append(presented.contains(key)?'!':'?')
                    .append(draft==null?'?':draft?'+':'-');
        }
        return out.toString();
    }
    void readReview(String text) {
        if(text.equals("1")) return;
        if(!text.startsWith("1\n")) throw new IllegalArgumentException("review version");
        for(String line:text.substring(2).split("\n")) {
            // Also recover already-written legacy strings with XML indentation.
            if(line.trim().isEmpty()) continue;
            int len=line.length();
            if(len<3) throw new IllegalArgumentException("review entry");
            String key=line.substring(0,len-2);
            char seen=line.charAt(len-2),draft=line.charAt(len-1);
            if((seen!='!'&&seen!='?')||(draft!='+'&&draft!='-'&&draft!='?'))
                throw new IllegalArgumentException("review choice");
            if(seen=='!') presented.add(key);
            if(draft!='?') drafts.put(key,draft=='+');
        }
    }
    String decisionsText() {
        StringBuilder out=new StringBuilder();
        for(java.util.Map.Entry<String,Boolean> entry:decided.entrySet()) {
            if(out.length()>0) out.append(',');
            out.append(entry.getKey()).append(entry.getValue()?'+':'-');
        }
        return out.toString();
    }
    /** Reads the existing decided|device format without evicting any decision. */
    void readDecisions(String text) {
        if(text.isEmpty()) return;
        for(String item:text.split(",",-1)) {
            int len=item.length();
            if(len<2) throw new IllegalArgumentException("decision entry");
            char choice=item.charAt(len-1);
            if(choice!='+'&&choice!='-') throw new IllegalArgumentException("decision choice");
            decided.put(item.substring(0,len-1),choice=='+');
        }
    }
    static String identity(byte[] raw) {
        if(raw==null||raw.length<10) throw new IllegalArgumentException("short record");
        StringBuilder out=new StringBuilder(22);
        out.append((raw[1]&255)|((raw[2]&255)<<8)).append('_');
        for(int i=3;i<10;i++) {
            out.append(Character.forDigit((raw[i]>>4)&15,16));
            out.append(Character.forDigit(raw[i]&15,16));
        }
        return out.toString();
    }
    interface Persistence<T> { boolean write(MeterDecisionState<T> proposed); }
    static final int IMPORT_REJECTED=0,IMPORT_PERSISTED=1,IMPORT_ALREADY_PRESENT=2;
    static boolean importConfirmed(int receipt) {
        return receipt==IMPORT_PERSISTED||receipt==IMPORT_ALREADY_PRESENT;
    }
    interface Importer<T> { int add(String key,T record) throws Exception; }
    /** Read-only preparation; only a newly committed explicit Add may call this. */
    interface Preparer<T> { String prepare(String key,T record) throws Exception; }
    final LinkedHashMap<String,String> bindings=new LinkedHashMap<>();
    /** Old unbound Adds the user (or an exact match) settled as already in the log. */
    final java.util.LinkedHashSet<String> resolved=new java.util.LinkedHashSet<>();
    /** A waiting reading an older version accepted without a conversion binding. */
    boolean isLegacy(String key) {
        return pending.containsKey(key)&&Boolean.TRUE.equals(decided.get(key))&&
            !bindings.containsKey(key)&&!resolved.contains(key);
    }
    /** Only waiting rows are actionable; an old completed Add is not a problem to report. */
    boolean hasLegacyAdds() {
        for(String key:pending.keySet())
            if(isLegacy(key)) return true;
        return false;
    }
    String resolvedText() {
        StringBuilder out=new StringBuilder("1");
        for(String key:resolved) out.append('\n').append(key);
        return out.toString();
    }
    void readResolved(String text) {
        if(text.equals("1")) return;
        if(!text.startsWith("1\n")) throw new IllegalArgumentException("resolved version");
        for(String line:text.substring(2).split("\n")) {
            if(line.trim().isEmpty()) continue; // preference XML indentation
            if(line.indexOf(',')>=0||!line.equals(line.trim())) throw new IllegalArgumentException("resolved entry");
            resolved.add(line);
        }
    }
    /** Seconds within which a saved row counts as the same reading: the meter clock offset
     *  is re-measured on every connection, so it drifts by seconds between syncs. */
    static final long SAME_READING_SECONDS=120;
    /** Index into found of the saved row that is this reading, or -1. found={expected,(time,slot,unit)*}. */
    static int sameReading(long[] found,java.util.Set<String> claimed) {
        if(found==null||found.length<4||(found.length-1)%3!=0) return -1;
        int best=-1;long distance=Long.MAX_VALUE;
        for(int i=1;i<found.length;i+=3) {
            long d=Math.abs(found[i]-found[0]);
            if(d<=SAME_READING_SECONDS&&d<distance&&(claimed==null||!claimed.contains(found[i+1]+":"+found[i]))) { best=i; distance=d; }
        }
        return best;
    }
    interface Matcher<T> { long[] find(String key,T record) throws Exception; }
    /** Settle waiting old Adds whose reading is already stored at (nearly) the same time.
     *  One saved row settles at most one waiting reading. Writes nothing native. */
    int autoResolve(Matcher<T> matcher,Persistence<T> disk) {
        int done=0;
        java.util.HashSet<String> claimed=new java.util.HashSet<>();
        for(String key:new java.util.ArrayList<>(pending.keySet())) {
            if(!isLegacy(key)) continue;
            long[] found;
            try { found=matcher.find(key,pending.get(key)); }
            catch(Exception failure) { continue; }
            int at=sameReading(found,claimed);
            if(at<0) continue;
            if(!resolveAlreadySaved(key,disk)) break; // storage failed: stop, nothing half-done
            claimed.add(found[at+1]+":"+found[at]);
            done++;
        }
        return done;
    }
    /** User: it is the reading already in the log. Nothing is written natively. */
    boolean resolveAlreadySaved(String key,Persistence<T> disk) {
        if(!isLegacy(key)) return false;
        MeterDecisionState<T> proposed=copy();
        proposed.resolved.add(key);
        proposed.removePending(key);
        if(!persist(proposed,disk)) return false;
        adopt(proposed);
        return true;
    }
    /** User: do not add it. Becomes a remembered exclusion; a resync cannot bring it back. */
    boolean resolveDiscard(String key,Persistence<T> disk) {
        if(!isLegacy(key)) return false;
        MeterDecisionState<T> proposed=copy();
        proposed.decided.put(key,false);
        proposed.removePending(key);
        if(!persist(proposed,disk)) return false;
        adopt(proposed);
        return true;
    }
    /** User: add it now, with today's settings. Fresh consent: binding durable before import. */
    boolean resolveAddNow(String key,Persistence<T> disk,Preparer<T> preparer,Importer<T> importer) {
        if(!isLegacy(key)) return false;
        String binding;
        try { binding=preparer.prepare(key,pending.get(key)); }
        catch(Exception failure) { return false; }
        if(binding==null) return false;
        MeterDecisionState<T> proposed=copy();
        proposed.bindings.put(key,binding);
        if(!persist(proposed,disk)) return false;
        adopt(proposed);
        // From here it is an ordinary bound Add: an uncertain import stays pending for Save retry.
        try { if(!importConfirmed(importer.add(key,pending.get(key)))) return false; }
        catch(Exception failure) { return false; }
        proposed=copy();
        proposed.removePending(key);
        if(!persist(proposed,disk)) return false;
        adopt(proposed);
        return true;
    }
    String bindingsText() {
        StringBuilder out=new StringBuilder("1");
        for(java.util.Map.Entry<String,String> entry:bindings.entrySet())
            out.append('\n').append(entry.getKey()).append(',').append(entry.getValue());
        return out.toString();
    }
    void readBindings(String text) {
        if(text.equals("1")) return;
        if(!text.startsWith("1\n")) throw new IllegalArgumentException("binding version");
        for(String line:text.substring(2).split("\n",-1)) {
            int comma=line.indexOf(',');
            if(comma<=0) throw new IllegalArgumentException("binding entry");
            String key=line.substring(0,comma),encoded=line.substring(comma+1);
            MeterImportBinding.decode(encoded,key,null);
            if(bindings.putIfAbsent(key,encoded)!=null) throw new IllegalArgumentException("duplicate binding");
        }
    }
    enum Route { BYPASS, ADD, HOLD, NEW, DONE }
    boolean storageFailed;
    private boolean persist(MeterDecisionState<T> proposed,Persistence<T> disk) {
        try { storageFailed=!disk.write(proposed); }
        catch(RuntimeException failure) { storageFailed=true; }
        return !storageFailed;
    }
    Route route(String key,boolean enabled) {
        // Protect exclusions and existing pending even if the preference is disabled.
        if(storageFailed||Boolean.FALSE.equals(decided.get(key))||pending.containsKey(key)) return Route.HOLD;
        // Settled as already in the log: a resync must not resurrect it.
        if(Boolean.TRUE.equals(decided.get(key))) return resolved.contains(key)?Route.DONE:Route.ADD;
        return enabled?Route.NEW:Route.BYPASS;
    }
    boolean commit(Collection<String> keys,Persistence<T> disk,Preparer<T> preparer,Importer<T> importer) {
        // Snapshot the visible identities; arrivals during review are never implicitly decided.
        java.util.ArrayList<String> selected=new java.util.ArrayList<>(keys);
        MeterDecisionState<T> proposed=copy();
        boolean complete=true;
        for(String key:selected) {
            Boolean add=drafts.get(key);
            if(add==null||!pending.containsKey(key)||decided.containsKey(key)) continue;
            if(add) {
                try {
                    String binding=preparer.prepare(key,pending.get(key));
                    if(binding==null) { complete=false; continue; }
                    proposed.bindings.put(key,binding);
                }
                catch(Exception failure) { complete=false; continue; }
            }
            proposed.decided.put(key,add);
            if(!add) proposed.removePending(key);
        }
        // Consent/exclusions must be durable BEFORE any import. No eviction of exclusions.
        if(!persist(proposed,disk)) return false;
        adopt(proposed);
        for(String key:selected) {
            T record=pending.get(key);
            if(record==null||!Boolean.TRUE.equals(decided.get(key))) continue;
            // Old committed Add is not fresh consent. Its prior converted row
            // may already exist; never calculate a new binding and auto-insert.
            if(!bindings.containsKey(key)) { complete=false; continue; }
            try {
                if(!importConfirmed(importer.add(key,record))) { complete=false; continue; }
            }
            catch(Exception failure) { complete=false; break; }
            proposed=copy();
            proposed.removePending(key);
            if(!persist(proposed,disk)) return false;
            adopt(proposed);
        }
        return complete;
    }
    private void removePending(String key) {
        pending.remove(key);
        drafts.remove(key);
        presented.remove(key);
    }
    private void adopt(MeterDecisionState<T> other) {
        pending.clear(); pending.putAll(other.pending);
        decided.clear(); decided.putAll(other.decided);
        bindings.clear(); bindings.putAll(other.bindings);
        resolved.clear(); resolved.addAll(other.resolved);
        drafts.clear(); drafts.putAll(other.drafts);
        presented.clear(); presented.addAll(other.presented);
    }
    final LinkedHashMap<String,T> pending=new LinkedHashMap<>();
    final LinkedHashMap<String,Boolean> decided=new LinkedHashMap<>();
    final LinkedHashMap<String,Boolean> drafts=new LinkedHashMap<>();
    boolean draft(Collection<String> keys,boolean add,Persistence<T> disk) {
        MeterDecisionState<T> proposed=copy();
        boolean changed=false;
        for(String key:keys) if(pending.containsKey(key)&&!decided.containsKey(key)) {
            proposed.drafts.put(key,add);
            changed=true;
        }
        if(!changed) return false;
        if(!persist(proposed,disk)) return false;
        drafts.clear();
        drafts.putAll(proposed.drafts);
        return true;
    }
    boolean draft(String key,boolean add,Persistence<T> disk) {
        return pending.containsKey(key)&&draft(java.util.Collections.singleton(key),add,disk);
    }
    MeterDecisionState<T> copy() {
        MeterDecisionState<T> copy=new MeterDecisionState<>();
        copy.pending.putAll(pending);
        copy.decided.putAll(decided);
        copy.bindings.putAll(bindings);
        copy.resolved.addAll(resolved);
        copy.drafts.putAll(drafts);
        copy.presented.addAll(presented);
        return copy;
    }
    final java.util.HashSet<String> presented=new java.util.HashSet<>();
    java.util.List<String> reviewKeys(boolean explicit) {
        java.util.ArrayList<String> keys=new java.util.ArrayList<>();
        for(String key:pending.keySet()) if(explicit||!presented.contains(key)) keys.add(key);
        return keys;
    }
    boolean hasUnseen() {
        for(String key:pending.keySet()) if(!presented.contains(key)) return true;
        return false;
    }
    boolean present(Collection<String> keys,Persistence<T> disk) {
        MeterDecisionState<T> proposed=copy();
        for(String key:keys) if(pending.containsKey(key)) proposed.presented.add(key);
        if(!persist(proposed,disk)) return false;
        presented.clear();
        presented.addAll(proposed.presented);
        return true;
    }
}
