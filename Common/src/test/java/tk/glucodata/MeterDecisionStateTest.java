package tk.glucodata;

import java.util.Arrays;


/** Synthetic identities/payloads only; run with -ea, no Android or device. */
public final class MeterDecisionStateTest {
    private static int passed;
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    private static final class Disk implements MeterDecisionState.Persistence<String> {
        MeterDecisionState<String> saved;
        boolean fail;
        int writes;
        int failAt=-1;
        public boolean write(MeterDecisionState<String> state) {
            if(++writes==failAt||fail) return false;
            saved=state.copy();
            return true;
        }
    }
    private static void laterDoesNotRepeat() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("1_timeA","synthetic");
        check(state.hasUnseen(),"new pending must prompt");
        check(state.present(Arrays.asList("1_timeA"),disk),"presentation persisted");
        check(!state.hasUnseen(),"Later/cancel must not re-prompt already presented pending");
        state=disk.saved.copy();
        check(!state.hasUnseen(),"restart must not re-prompt presented pending");
        state.pending.put("1_timeA","synthetic resync");
        check(!state.hasUnseen(),"resync must not re-prompt same identity");
        check(state.pending.size()==1,"explicit review must still have deferred reading");
        check(state.reviewKeys(true).equals(Arrays.asList("1_timeA")),"explicit show reviews presented/deferred pending");
        check(state.reviewKeys(false).isEmpty(),"automatic show must not repeat deferred pending");
        state.pending.put("2_timeA","new synthetic");
        check(state.hasUnseen(),"new unseen reading must still prompt");
        check(state.reviewKeys(false).equals(Arrays.asList("2_timeA")),"new arrival must not bring deferred rows back to automatic popup");
        passed++;
    }
    private static void draftSurvivesLaterAndRestart() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("7_timeA","synthetic");
        state.present(Arrays.asList("7_timeA"),disk);
        check(state.draft("7_timeA",true,disk),"draft Add must persist immediately");
        state=disk.saved.copy();
        check(Boolean.TRUE.equals(state.drafts.get("7_timeA")),"Later/restart must preserve Add draft");
        check(state.decided.isEmpty(),"draft choice is not import consent");
        check(state.draft("7_timeA",false,disk),"Exclude draft must persist");
        state=disk.saved.copy();
        check(Boolean.FALSE.equals(state.drafts.get("7_timeA")),"restart must preserve Exclude draft");
        check(state.pending.size()==1,"draft Exclude must not drop reading before Save");
        passed++;
    }
    private static void onlySaveCommitsExplicitChoices() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("add","synthetic add");
        state.pending.put("exclude","synthetic exclude");
        state.pending.put("untouched","synthetic pending");
        state.draft("add",true,disk);
        state.draft("exclude",false,disk);
        java.util.ArrayList<String> imported=new java.util.ArrayList<>();
        check(state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(key,value) -> {
            check(Boolean.TRUE.equals(disk.saved.decided.get(key)),"durable Add consent before import");
            check(!disk.saved.pending.containsKey("exclude"),"excluded raw payload must not persist");
            imported.add(key);
            return MeterDecisionState.IMPORT_PERSISTED;
        }),"Save choices must succeed");
        check(imported.equals(Arrays.asList("add")),"only explicitly added reading imported");
        check(Boolean.FALSE.equals(state.decided.get("exclude")),"Exclude remembered");
        check(state.pending.size()==1&&state.pending.containsKey("untouched"),"unselected stays pending");
        state=disk.saved.copy();
        check(state.route("exclude",true)==MeterDecisionState.Route.HOLD,"resync must not add excluded");
        check(state.route("exclude",false)==MeterDecisionState.Route.HOLD,"disabling ask must not release exclusion");
        check(state.route("untouched",false)==MeterDecisionState.Route.HOLD,"disabling ask must not release pending");
        check(state.route("add",true)==MeterDecisionState.Route.ADD,"committed Add remembered after restart");
        check(state.route("new",false)==MeterDecisionState.Route.BYPASS,"opt-out unchanged for unseen records");
        passed++;
    }
    private static void persistenceFailuresAreClosed() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("one","synthetic");
        state.draft("one",true,disk);
        disk.fail=true;
        int[] imported={0};
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> { imported[0]++; return MeterDecisionState.IMPORT_PERSISTED; }),"failed Save reports failure");
        check(imported[0]==0&&state.decided.isEmpty(),"failed durability must not import or authorize");
        check(state.route("new",false)==MeterDecisionState.Route.HOLD,"storage failure must block fallback saving");
        check(!state.draft("one",false,s -> { throw new IllegalStateException("synthetic disk error"); }),"exception must fail closed");
        check(Boolean.TRUE.equals(state.drafts.get("one")),"failed draft write must not replace durable choice");
        disk.fail=false;
        check(state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> { imported[0]++; return MeterDecisionState.IMPORT_PERSISTED; }),"successful retry recovers");
        check(imported[0]==1,"retry imports only after confirmed storage");
        passed++;
    }
    private static void excludeAllNeverEvicts() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        for(int i=0;i<2505;i++) {
            String key=i+"_timeA";
            state.pending.put(key,"synthetic payload "+i);
        }
        check(state.draft(state.pending.keySet(),false,disk),"Exclude all drafts persist atomically");
        check(state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> {throw new AssertionError("excluded imported");}),"exclude all commits");
        state=new MeterDecisionState<>();
        state.readDecisions(disk.saved.decisionsText());
        state.readReview(disk.saved.reviewText());
        check(state.decided.size()==2505,"more than 2000 exclusions retained");
        check(state.pending.isEmpty()&&state.drafts.isEmpty()&&state.presented.isEmpty(),"no excluded raw values retained");
        for(int i=0;i<2505;i++) check(state.route(i+"_timeA",false)==MeterDecisionState.Route.HOLD,"old exclusion protected after restart");
        MeterDecisionState<String> otherDevice=new MeterDecisionState<>();
        check(otherDevice.route("0_timeA",true)==MeterDecisionState.Route.NEW,"no cross-device decisions");
        check(state.route("0_timeB",true)==MeterDecisionState.Route.NEW,"same sequence new base time is new identity");
        passed++;
    }
    private static void metadataRoundTripAndWireIdentity() {
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("7_timeA","synthetic");
        state.presented.add("7_timeA");
        state.drafts.put("7_timeA",true);
        state.decided.put("8_timeA",false);
        MeterDecisionState<String> restart=new MeterDecisionState<>();
        restart.pending.putAll(state.pending); // raw pending file is separate, as in MeterAsk
        restart.readReview(state.reviewText());
        restart.readDecisions(state.decisionsText());
        check(Boolean.TRUE.equals(restart.drafts.get("7_timeA")),"on-disk draft survives restart");
        check(!restart.hasUnseen(),"on-disk presented marker survives restart");
        check(restart.route("8_timeA",false)==MeterDecisionState.Route.HOLD,"on-disk exclusion survives restart");
        byte[] first={0,42,0,(byte)0xe8,7,1,2,3,4,5,6,7,8,9};
        byte[] later=first.clone(); later[9]++;
        check(!MeterDecisionState.identity(first).equals(MeterDecisionState.identity(later)),"same sequence new base time must not collide");
        later=first.clone(); later[10]++;
        check(MeterDecisionState.identity(first).equals(MeterDecisionState.identity(later)),"value/offset bytes do not change identity");
        passed++;
    }
    private static void committedAddCannotBecomeAnUnsafeUndo() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("one","synthetic");
        state.draft("one",true,disk);
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> {throw new IllegalStateException("synthetic import interruption");}),"interrupted import reported");
        state=disk.saved.copy();
        check(state.pending.containsKey("one")&&Boolean.TRUE.equals(state.decided.get("one")),"durable consent and raw retry retained");
        check(!state.draft("one",false,disk),"committed Add cannot offer unsafe Exclude/undo after uncertain native import");
        check(state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> MeterDecisionState.IMPORT_ALREADY_PRESENT),"explicit review can retry committed Add");
        check(state.pending.isEmpty(),"successful retry removes pending payload");
        passed++;
    }
    private static void cleanupFailureRetainsCommittedRetryWithoutUndo() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("one","synthetic");
        state.draft("one",true,disk);
        disk.failAt=disk.writes+2; // consent succeeds, post-import cleanup fails
        int[] imported={0};
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> { imported[0]++; return MeterDecisionState.IMPORT_PERSISTED; }),"cleanup failure reported");
        check(imported[0]==1,"only durably authorized import executed");
        check(state.route("new",false)==MeterDecisionState.Route.HOLD,"failed cleanup also fails closed");
        state=disk.saved.copy();
        check(Boolean.TRUE.equals(state.decided.get("one"))&&state.pending.containsKey("one"),"restart retains uncertain committed retry");
        check(!state.draft("one",false,disk),"no unsafe undo after possible native success");
        passed++;
    }
    private static void androidPreferenceWhitespaceRoundTrip() {
        MeterDecisionState<String> state=new MeterDecisionState<>();
        check(!state.reviewText().endsWith("\n"),"empty review must not end in a newline in Android preferences");
        state.readReview("1\n    "); // Android preference XML indentation after a trailing newline
        check(state.presented.isEmpty()&&state.drafts.isEmpty(),"indented legacy empty review is not corruption");
        state.pending.put("7_timeA","synthetic");
        state.presented.add("7_timeA");
        state.drafts.put("7_timeA",false);
        check(!state.reviewText().endsWith("\n"),"populated review must not end in a newline");
        MeterDecisionState<String> restart=new MeterDecisionState<>();
        restart.pending.putAll(state.pending);
        restart.readReview("1\n7_timeA!-\n    ");
        check(restart.presented.contains("7_timeA")&&Boolean.FALSE.equals(restart.drafts.get("7_timeA")),"legacy indented metadata preserves choices");
        restart.readReview(state.reviewText());
        boolean refused=false;
        try {restart.readReview("1\n7_timeA!x\n    ");}catch(IllegalArgumentException expected){refused=true;}
        check(refused,"nonblank malformed decisions must still fail closed");
        passed++;
    }
    private static int rejectedReceipt() { return 0; }
    private static void rejectedImportRetainsPendingAfterRestart() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=new MeterDecisionState<>();
        state.pending.put("older","synthetic older payload");
        state.draft("older",true,disk);
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> rejectedReceipt()),"rejected native import must not report completion");
        state=disk.saved.copy();
        check(state.pending.containsKey("older")&&Boolean.TRUE.equals(state.decided.get("older")),"restart retains authorized rejected raw payload for retry");
        check(!state.draft("older",false,disk),"uncertain import cannot become unsafe undo");
        check(state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> MeterDecisionState.IMPORT_ALREADY_PRESENT),"already-present durable receipt completes retry");
        check(state.pending.isEmpty(),"already-present retry cleans up payload");
        passed++;
    }
    private static void unknownReceiptAndPartialBatchStayRetryable() {
        Disk disk=new Disk();MeterDecisionState<String> state=new MeterDecisionState<>();
        for(String key:Arrays.asList("unknown","rejected","persisted","present")) {
            state.pending.put(key,"synthetic");state.draft(key,true,disk);
        }
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(key,value) -> {
            check(Boolean.TRUE.equals(disk.saved.decided.get(key)),"every import has durable consent");
            switch(key) {
                case "unknown": return 12345;
                case "rejected": return MeterDecisionState.IMPORT_REJECTED;
                case "present": return MeterDecisionState.IMPORT_ALREADY_PRESENT;
                default: return MeterDecisionState.IMPORT_PERSISTED;
            }
        }),"partial failed batch must not claim completion");
        MeterDecisionState<String> restart=new MeterDecisionState<>();
        restart.pending.putAll(disk.saved.pending);
        restart.readDecisions(disk.saved.decisionsText());restart.readReview(disk.saved.reviewText());
        check(restart.pending.keySet().equals(new java.util.LinkedHashSet<>(Arrays.asList("unknown","rejected"))),"only unreceipted payloads retained through serialization/restart");
        check(!restart.draft("unknown",false,disk)&&!restart.draft("rejected",false,disk),"uncertain imports cannot undo consent");
        passed++;
    }
    private static void legacyUnboundAddNeverAutomaticallyImports() {
        Disk disk=new Disk(); MeterDecisionState<String> state=new MeterDecisionState<>();
        state.readDecisions("legacy+");
        state.pending.put("legacy","possible prior successful write");
        int[] calls={0};
        check(!state.commit(state.pending.keySet(),disk,(k,v) -> "fixture:"+k,(k,v) -> {calls[0]++; return 1;}),"legacy unbound Add must defer, not reinterpret");
        check(calls[0]==0&&state.pending.containsKey("legacy"),"legacy migration neither duplicates nor silently drops retry");
        passed++;
    }
    private static MeterDecisionState<String> legacyState(Disk disk,String... keys) {
        MeterDecisionState<String> state=new MeterDecisionState<>();
        StringBuilder decided=new StringBuilder();
        for(String key:keys) { if(decided.length()>0) decided.append(','); decided.append(key).append('+'); state.pending.put(key,"synthetic"); }
        state.readDecisions(decided.toString());
        check(state.hasLegacyAdds(),"fixture is an old unbound Add");
        return state;
    }
    private static void legacyResolveKeepWritesNothingAndSurvivesResync() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=legacyState(disk,"9_t");
        int[] imports={0};
        check(state.resolveAlreadySaved("9_t",disk),"keep resolves");
        check(!state.pending.containsKey("9_t")&&!state.hasLegacyAdds(),"keep clears the hold");
        check(imports[0]==0,"keep imports nothing");
        MeterDecisionState<String> restart=new MeterDecisionState<>();
        restart.readDecisions(disk.saved.decisionsText());
        restart.readResolved(disk.saved.resolvedText());
        check(restart.route("9_t",true)==MeterDecisionState.Route.DONE,"a resync of a kept reading is never re-added");
        check(!restart.resolveAlreadySaved("9_t",disk),"resolving twice is refused");
        passed++;
    }
    private static void legacyResolveDiscardBecomesExclusion() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=legacyState(disk,"9_t");
        check(state.resolveDiscard("9_t",disk),"discard resolves");
        check(Boolean.FALSE.equals(state.decided.get("9_t"))&&!state.pending.containsKey("9_t"),"discard is a durable Exclude");
        check(state.route("9_t",true)==MeterDecisionState.Route.HOLD,"a resync of a discarded reading is held, not saved");
        passed++;
    }
    private static void legacyResolveAddNowBindsBeforeImport() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=legacyState(disk,"9_t");
        boolean[] boundFirst={false};
        check(state.resolveAddNow("9_t",disk,(k,v) -> "fixture:"+k,(k,v) -> { boundFirst[0]=disk.saved.bindings.containsKey(k); return 1; }),"add now imports");
        check(boundFirst[0],"binding is durable before the native write");
        check(!state.pending.containsKey("9_t")&&state.bindings.containsKey("9_t"),"added and bound");
        // An uncertain import keeps it pending as an ordinary bound Add for Save retry, never legacy again.
        Disk other=new Disk();
        MeterDecisionState<String> uncertain=legacyState(other,"8_t");
        check(!uncertain.resolveAddNow("8_t",other,(k,v) -> "fixture:"+k,(k,v) -> 0),"unknown receipt is not success");
        check(uncertain.pending.containsKey("8_t")&&!uncertain.isLegacy("8_t"),"retryable through the normal bound path");
        // A preparer failure leaves it legacy and untouched.
        Disk third=new Disk();
        MeterDecisionState<String> refused=legacyState(third,"7_t");
        check(!refused.resolveAddNow("7_t",third,(k,v) -> null,(k,v) -> 1),"no binding, no import");
        check(refused.isLegacy("7_t"),"still waiting for a choice");
        passed++;
    }
    private static void legacyResolveStorageFailureChangesNothing() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=legacyState(disk,"9_t");
        disk.fail=true;
        check(!state.resolveAlreadySaved("9_t",disk)&&state.isLegacy("9_t"),"failed keep leaves the hold");
        check(!state.resolveDiscard("9_t",disk)&&state.isLegacy("9_t"),"failed discard leaves the hold");
        int[] calls={0};
        check(!state.resolveAddNow("9_t",disk,(k,v) -> "fixture:"+k,(k,v) -> {calls[0]++; return 1;})&&calls[0]==0,"no native write without a durable binding");
        check(!state.resolveAlreadySaved("absent",disk)&&!state.resolveAlreadySaved("unknown",disk),"only waiting legacy rows resolve");
        passed++;
    }
    private static void legacyAutoResolveOnlySameTime() {
        Disk disk=new Disk();
        MeterDecisionState<String> state=legacyState(disk,"a","b","c","d","e");
        java.util.Map<String,long[]> found=new java.util.HashMap<>();
        found.put("a",new long[]{1000,1000+60,0,1});        // same reading, clock drift inside 2 min
        found.put("b",new long[]{2000,2000+18000,0,1});     // same value five hours away: user must decide
        found.put("c",new long[]{3000});                    // nothing found: user must decide
        found.put("d",null);                                // cannot check: untouched
        found.put("e",new long[]{1000,1000+60,0,1});        // same row as "a": one row settles one reading
        int n=state.autoResolve((k,v) -> found.get(k),disk);
        check(n==1&&!state.pending.containsKey("a"),"only an exact same-time match settles itself");
        check(state.isLegacy("b")&&state.isLegacy("c")&&state.isLegacy("d")&&state.isLegacy("e"),"everything else stays for the user");
        check(MeterDecisionState.sameReading(new long[]{5,5,0,1,7},null)==-1,"malformed search result is no match");
        check(MeterDecisionState.sameReading(new long[]{5,5+121,0,1},null)==-1,"outside two minutes is no match");
        passed++;
    }
    public static void main(String[] args) {
        legacyUnboundAddNeverAutomaticallyImports();
        legacyResolveKeepWritesNothingAndSurvivesResync();
        legacyResolveDiscardBecomesExclusion();
        legacyResolveAddNowBindsBeforeImport();
        legacyResolveStorageFailureChangesNothing();
        legacyAutoResolveOnlySameTime();
        unknownReceiptAndPartialBatchStayRetryable();
        rejectedImportRetainsPendingAfterRestart();
        androidPreferenceWhitespaceRoundTrip();
        laterDoesNotRepeat();
        draftSurvivesLaterAndRestart();
        onlySaveCommitsExplicitChoices();
        persistenceFailuresAreClosed();
        excludeAllNeverEvicts();
        metadataRoundTripAndWireIdentity();
        committedAddCannotBecomeAnUnsafeUndo();
        cleanupFailureRetainsCommittedRetryWithoutUndo();
        System.out.println("MeterDecisionStateTest: "+passed+" passed");
    }
}
