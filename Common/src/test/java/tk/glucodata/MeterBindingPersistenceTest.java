package tk.glucodata;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import java.io.StringReader;
import java.io.StringWriter;

/** Synthetic native binding -> production Java codecs/state -> native binding.
 * Disk is real host files, not Android SharedPreferences; importer callbacks are
 * deliberately controlled here. Native row insertion is tested by the C++ runner.
 */
public final class MeterBindingPersistenceTest {
    private static int passed;
    private static void check(boolean b,String message) { if(!b)throw new AssertionError(message); }
    private static final class Disk implements MeterDecisionState.Persistence<byte[]> {
        final Path file;
        boolean fail;
        int writes,failAt=-1;
        Disk(Path file) {this.file=file;}
        public boolean write(MeterDecisionState<byte[]> s) {
            if(++writes==failAt||fail)return false;
            try {
                Properties p=new Properties();
                p.setProperty("decisions",s.decisionsText());p.setProperty("review",s.reviewText());p.setProperty("bindings",s.bindingsText());
                for(String key:s.pending.keySet()) p.setProperty("pending."+key,Base64.getEncoder().encodeToString(s.pending.get(key)));
                StringWriter out=new StringWriter();p.store(out,null);
                Files.writeString(file,out.toString());
                try(FileChannel f=FileChannel.open(file,StandardOpenOption.WRITE)){f.force(true);}
                return true;
            }catch(Exception e){throw new IllegalStateException(e);}
        }
        MeterDecisionState<byte[]> restart() {
            try {
                Properties p=new Properties();p.load(new StringReader(Files.readString(file)));
                MeterDecisionState<byte[]> s=new MeterDecisionState<>();
                s.readDecisions(p.getProperty("decisions"));s.readReview(p.getProperty("review"));s.readBindings(p.getProperty("bindings"));
                for(String key:p.stringPropertyNames()) if(key.startsWith("pending.")) s.pending.put(key.substring(8),Base64.getDecoder().decode(p.getProperty(key)));
                return s;
            }catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    private static void sign(byte[] b) {
        int hash=0x811c9dc5;for(int i=0;i<b.length-4;i++)hash=(hash^(b[i]&255))*0x01000193;
        ByteBuffer.wrap(b).putInt(b.length-4,hash);
    }
    private static byte[] fixture() {
        byte[] raw={2,42,0,(byte)0xe8,7,1,2,12,0,0,0x64,(byte)0xb0,0,0};
        byte[] b=new byte[86+raw.length+4];ByteBuffer out=ByteBuffer.wrap(b);
        out.put(new byte[]{'J','M','I','1'}).putInt(1704196800).putInt(Float.floatToRawIntBits(100)).putInt(3).putInt(2).putInt(1704196800).putInt(0);
        out.put("Synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.arraycopy("synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,b,73,9);
        b[85]=(byte)raw.length;System.arraycopy(raw,0,b,86,raw.length);sign(b);return b;
    }
    private static void refuses(Runnable call,String why) {
        boolean refused=false;try{call.run();}catch(IllegalArgumentException expected){refused=true;}check(refused,why);
    }
    private static void codec(byte[] b,String key,byte[] raw) {
        String encoded=MeterImportBinding.encode(b,key,raw);
        check(Arrays.equals(b,MeterImportBinding.decode(encoded,key,raw)),"native binary/text exact bits roundtrip");
        MeterImportBinding.requireDevice(b,"Synthetic");
        refuses(() -> MeterImportBinding.requireDevice(b,"Other"),"wrong Java device scope refused");
        refuses(() -> MeterImportBinding.decode(encoded,"bad_identity",raw),"wrong stored identity refused");
        byte[] wrong=raw.clone();wrong[10]++;
        refuses(() -> MeterImportBinding.decode(encoded,key,wrong),"same identity/different payload refused");
        for(int i=0;i<b.length;i++) {
            byte[] corrupt=b.clone();corrupt[i]^=1;
            refuses(() -> MeterImportBinding.encode(corrupt,key,raw),"corrupt field/checksum refused");
        }
        for(int at:new int[]{4,8,12,16}) {
            byte[] corrupt=b.clone();ByteBuffer buf=ByteBuffer.wrap(corrupt);
            buf.putInt(at,at==8?Float.floatToRawIntBits(Float.NaN):at==12?40:at==16?3:0);sign(corrupt);
            refuses(() -> MeterImportBinding.encode(corrupt,key,raw),"signed out-of-bounds tuple refused");
        }
        MeterDecisionState<byte[]> state=new MeterDecisionState<>();
        refuses(() -> state.readBindings("1\n"+key+","+encoded+"\n"+key+","+encoded),"duplicate persisted identity refused");
        refuses(() -> new MeterDecisionState<byte[]>().readBindings("2"),"unknown binding format refused");
        passed++;
    }
    private static byte[] lifecycle(Disk disk,byte[] b,String key,byte[] raw) {
        String encoded=MeterImportBinding.encode(b,key,raw);
        MeterDecisionState<byte[]> s=new MeterDecisionState<>();s.pending.put(key,raw);s.draft(key,true,disk);
        check(s.decided.isEmpty()&&s.bindings.isEmpty(),"draft/preparation is not consent");
        check(!s.commit(s.pending.keySet(),disk,(k,r) -> encoded,(k,r) -> {
            MeterDecisionState<byte[]> durable=disk.restart();
            check(Boolean.TRUE.equals(durable.decided.get(k))&&encoded.equals(durable.bindings.get(k)),"consent and exact binding durable before any import callback");
            return 0;
        }),"uncertain import retains pending");
        s=disk.restart();check(s.pending.containsKey(key)&&encoded.equals(s.bindings.get(key)),"restart retains raw and first binding");
        check(s.commit(s.pending.keySet(),disk,(k,r) -> {throw new AssertionError("must not reprepare after uncertain write");},(k,r)->2),"frozen retry completes");
        s=disk.restart();check(s.pending.isEmpty()&&encoded.equals(s.bindings.get(key)),"cleanup retains binding for completed Add");
        check(s.route(key,false)==MeterDecisionState.Route.ADD,"completed remembered Add stages receipt replay even with review disabled");
        s.pending.put(key,raw);check(disk.write(s),"remembered Add retry raw staged durably");
        check(s.commit(s.pending.keySet(),disk,(k,r) -> {throw new AssertionError("completed Add must never reprepare");},(k,r)->2),"completed Add replay consumes retained binding");
        s=disk.restart();check(s.pending.isEmpty()&&encoded.equals(s.bindings.get(key)),"completed replay keeps binding and consent");
        passed++;return MeterImportBinding.decode(s.bindings.get(key),key,raw);
    }
    private static void failedCommitAndPreparation(Disk disk,byte[] b,String key,byte[] raw) {
        MeterDecisionState<byte[]> s=new MeterDecisionState<>();s.pending.put(key,raw);s.draft(key,true,disk);
        String encoded=MeterImportBinding.encode(b,key,raw);disk.fail=true;
        check(!s.commit(s.pending.keySet(),disk,(k,r)->encoded,(k,r)->{throw new AssertionError("binding commit failed but importer ran");}),"failed atomic binding/consent commit reported");
        check(s.bindings.isEmpty()&&s.decided.isEmpty()&&s.pending.containsKey(key),"failed commit neither binds nor authorizes");
        MeterDecisionState<byte[]> restart=disk.restart();check(restart.bindings.isEmpty()&&restart.decided.isEmpty(),"disk still has only draft after failed binding commit");
        disk.fail=false;
        check(!s.commit(s.pending.keySet(),disk,(k,r)->null,(k,r)->{throw new AssertionError("unprepared import");}),"invalid preparation fails closed");
        check(s.decided.isEmpty()&&s.pending.containsKey(key),"invalid preparation never grants consent");
        check(!s.commit(s.pending.keySet(),disk,(k,r)->{throw new IllegalStateException("prepare failed");},(k,r)->1),"preparation exception retained");
        check(s.decided.isEmpty(),"preparation exception has no consent");
        passed++;
    }
    private static void legacy(Disk disk,String key,byte[] raw) {
        MeterDecisionState<byte[]> s=new MeterDecisionState<>();s.readDecisions(key+"+");s.pending.put(key,raw);check(disk.write(s),"legacy pending snapshot");
        s=disk.restart();check(s.hasLegacyAdds(),"legacy unsafe conversion is visible as review limitation");
        for(int attempt=0;attempt<2;attempt++) {
            check(!s.commit(s.pending.keySet(),disk,(k,r)->{throw new AssertionError("legacy Add was reinterpreted as fresh consent");},(k,r)->{throw new AssertionError("legacy auto write");}),"legacy replay quarantined");
            s=disk.restart();check(s.pending.containsKey(key)&&Boolean.TRUE.equals(s.decided.get(key))&&s.bindings.isEmpty(),"legacy pending and consent not silently cleaned or rebound");
        }
        // Already-completed legacy Add: meter replay must also quarantine, not
        // auto-write under a new offset merely because pending used to be empty.
        s=new MeterDecisionState<>();s.readDecisions(key+"+");check(disk.write(s),"legacy completed snapshot");s=disk.restart();
        check(s.route(key,true)==MeterDecisionState.Route.ADD,"legacy replay is staged for explicit error/review");s.pending.put(key,raw);check(disk.write(s),"legacy replay raw retained");
        check(!s.commit(s.pending.keySet(),disk,(k,r)->{throw new AssertionError("completed legacy rebind");},(k,r)->1),"completed legacy auto replay deferred");
        check(disk.restart().pending.containsKey(key)&&s.hasLegacyAdds(),"completed legacy no duplicate/no silent payload loss");
        passed++;
    }
    public static void main(String[] args) throws Exception {
        byte[] nativeBinding=args.length==0?fixture():Files.readAllBytes(Path.of(args[0]));
        byte[] raw=Arrays.copyOfRange(nativeBinding,86,86+(nativeBinding[85]&255));String key=MeterDecisionState.identity(raw);
        Path scratch=Files.createTempDirectory(Path.of(System.getenv().getOrDefault("TMPDIR","/var/lib/hermes/cache/scratch")),"meter-binding-java-");
        try {
            Disk disk=new Disk(scratch.resolve("prefs"));codec(nativeBinding,key,raw);
            byte[] restored=lifecycle(disk,nativeBinding,key,raw);
            failedCommitAndPreparation(disk,nativeBinding,key,raw);legacy(disk,key,raw);
            if(args.length!=0)Files.write(Path.of(args[0]),restored);
            System.out.println("MeterBindingPersistenceTest: "+passed+" passed"+(args.length==0?"":" (native-generated bytes restored for native replay)"));
        } finally {Files.deleteIfExists(scratch.resolve("prefs"));Files.delete(scratch);}
    }
}
