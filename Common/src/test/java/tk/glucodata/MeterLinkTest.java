package tk.glucodata;

/** MeterLink decisions. Plain Java, run with -ea; no Android, Bluetooth or meter. */
public final class MeterLinkTest {
    private static int passed;
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    private static void alwaysKeepsTheOriginalBehaviour() {
        check(MeterLink.mayConnect(MeterLink.Mode.ALWAYS,false),"always connects in the background");
        check(MeterLink.release(MeterLink.Mode.ALWAYS,false,false)==MeterLink.Release.KEEP,"always never lets go");
        check(MeterLink.parse(null)==MeterLink.Mode.ALWAYS&&MeterLink.parse("garbage")==MeterLink.Mode.ALWAYS,"unknown or missing setting is the original behaviour");
        passed++;
    }
    private static void appOpenLetsGoOnlyBetweenTransfers() {
        check(MeterLink.mayConnect(MeterLink.Mode.APP_OPEN,true)&&!MeterLink.mayConnect(MeterLink.Mode.APP_OPEN,false),"app-open follows the app");
        check(MeterLink.release(MeterLink.Mode.APP_OPEN,true,false)==MeterLink.Release.KEEP,"in front: keep");
        check(MeterLink.release(MeterLink.Mode.APP_OPEN,false,true)==MeterLink.Release.WAIT,"never cut a transfer");
        check(MeterLink.release(MeterLink.Mode.APP_OPEN,false,false)==MeterLink.Release.CLOSE,"idle in the background: let go");
        check(MeterLink.parse(MeterLink.store(MeterLink.Mode.APP_OPEN))==MeterLink.Mode.APP_OPEN,"stored setting round-trips");
        passed++;
    }
    private static void transferWindow() {
        check(!MeterLink.transferring(0,0,1000),"never requested");
        check(MeterLink.transferring(1000,0,1500),"requested, no answer yet");
        check(!MeterLink.transferring(1000,1200,1500),"answered");
        check(MeterLink.transferring(5000,1200,5001),"a new request after an old answer");
        check(!MeterLink.transferring(1000,0,1000+MeterLink.TRANSFER_TIMEOUT_MS),"a meter that never answers does not hold the phone forever");
        check(!MeterLink.transferring(5000,0,4000),"clock moved back: not a transfer");
        passed++;
    }
    private static void fetchDecisions() {
        check(MeterLink.fetch(true,true,false,true,true)==MeterLink.Fetch.REQUEST,"connected and idle: ask again");
        check(MeterLink.fetch(true,true,true,true,true)==MeterLink.Fetch.BUSY,"never a second request during a transfer");
        check(MeterLink.fetch(false,true,false,true,true)==MeterLink.Fetch.BUSY,"pairing or discovering: let it finish");
        check(MeterLink.fetch(false,false,false,true,true)==MeterLink.Fetch.CONNECT,"not connected: try once directly");
        check(MeterLink.fetch(false,false,false,false,true)==MeterLink.Fetch.SKIPPED,"not allowed now");
        check(MeterLink.fetch(false,false,false,true,false)==MeterLink.Fetch.SKIPPED,"no address: nothing to connect to");
        passed++;
    }
    private static void outcomesAreHonest() {
        final long since=10_000;
        check(MeterLink.outcome(since,false,0,since+5,true)==MeterLink.Outcome.NEW,"answer with records");
        check(MeterLink.outcome(since,true,0,since+5,false)==MeterLink.Outcome.NONE,"answer without records");
        check(MeterLink.outcome(since,true,since-5000,since-100,true)==MeterLink.Outcome.SENDING,"an answer from before the pull does not count");
        check(MeterLink.outcome(since,false,since+10,0,false)==MeterLink.Outcome.SENDING,"connected during the pull, not answered");
        check(MeterLink.outcome(since,false,since-5,since-5,true)==MeterLink.Outcome.UNREACHED,"nothing since the pull");
        check(MeterLink.settled(MeterLink.Outcome.NEW)&&MeterLink.settled(MeterLink.Outcome.NONE),"answers settle");
        check(!MeterLink.settled(MeterLink.Outcome.SENDING)&&!MeterLink.settled(MeterLink.Outcome.UNREACHED),"no answer keeps waiting");
        check(MeterLink.best(MeterLink.Outcome.UNREACHED,MeterLink.Outcome.NEW)==MeterLink.Outcome.NEW,"best of several meters");
        check(MeterLink.best(null,MeterLink.Outcome.UNREACHED)==MeterLink.Outcome.UNREACHED&&MeterLink.best(null,null)==null,"none asked");
        passed++;
    }
    public static void main(String[] args) {
        alwaysKeepsTheOriginalBehaviour();
        appOpenLetsGoOnlyBetweenTransfers();
        transferWindow();
        fetchDecisions();
        outcomesAreHonest();
        System.out.println("MeterLinkTest: "+passed+" passed");
    }
}
