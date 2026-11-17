package tk.glucodata;

/** Versioned native conversion binding. Hex is canonical; no mutable arrays escape persistence. */
final class MeterImportBinding {
    private MeterImportBinding() {}
    static void requireDevice(byte[] b,String name) {
        byte[] expected=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(expected.length==0||expected.length>=39) throw new IllegalArgumentException("binding device name");
        for(int i=0;i<39;i++)
            if(b[28+i]!=(i<expected.length?expected[i]:0)) throw new IllegalArgumentException("binding device mismatch");
    }
    static final int RAW_AT=86, MAX_RAW=64;
    private static long u32(byte[] b,int at) {
        return ((long)(b[at]&255)<<24)|((long)(b[at+1]&255)<<16)|((b[at+2]&255)<<8)|(b[at+3]&255);
    }
    static String encode(byte[] bytes,String key,byte[] raw) {
        validate(bytes,key,raw);
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes) out.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
        return out.toString();
    }
    static byte[] decode(String text,String key,byte[] raw) {
        if(text==null||(text.length()&1)!=0||text.length()<208||text.length()>308)
            throw new IllegalArgumentException("binding size");
        byte[] bytes=new byte[text.length()/2];
        for(int i=0;i<bytes.length;i++) {
            char a=text.charAt(2*i),b=text.charAt(2*i+1);
            int hi=Character.digit(a,16),lo=Character.digit(b,16);
            if(hi<0||lo<0||a!=Character.forDigit(hi,16)||b!=Character.forDigit(lo,16))
                throw new IllegalArgumentException("binding hex");
            bytes[i]=(byte)((hi<<4)|lo);
        }
        validate(bytes,key,raw);
        return bytes;
    }
    private static void validate(byte[] b,String key,byte[] raw) {
        if(b==null||b.length<104||b.length>154||b[0]!='J'||b[1]!='M'||b[2]!='I'||b[3]!='1')
            throw new IllegalArgumentException("binding header");
        int n=b[85]&255;
        if(n<14||n>MAX_RAW||b.length!=RAW_AT+n+4) throw new IllegalArgumentException("binding length");
        int hash=0x811c9dc5;
        for(int i=0;i<b.length-4;i++) hash=(hash^(b[i]&255))*0x01000193;
        if((hash&0xffffffffL)!=u32(b,b.length-4)) throw new IllegalArgumentException("binding checksum");
        long tim=u32(b,4),original=u32(b,20),blood=u32(b,12),unit=u32(b,16);
        float value=Float.intBitsToFloat((int)u32(b,8));
        if(tim==0||original==0||blood>=40||(unit!=1&&unit!=2)||!Float.isFinite(value)||value<=0)
            throw new IllegalArgumentException("binding tuple bounds");
        byte[] saved=java.util.Arrays.copyOfRange(b,RAW_AT,RAW_AT+n);
        int minutes=(saved[0]&1)!=0?(short)((saved[10]&255)|((saved[11]&255)<<8)):0;
        long corrected=original+(int)u32(b,24)+(long)minutes*60;
        if(corrected!=tim||!MeterDecisionState.identity(saved).equals(key)||
            (raw!=null&&!java.util.Arrays.equals(saved,raw))) throw new IllegalArgumentException("binding identity/time");
        // Native also verifies device name/address, label bytes and raw conversion.
    }
}
