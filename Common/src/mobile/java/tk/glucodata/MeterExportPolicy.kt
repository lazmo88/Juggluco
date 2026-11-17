// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

internal data class MeterExportReading(val time:Long,val milliMgdl:Long) {
    val mgdl:Double get()=milliMgdl/1000.0
    val clientId:String get()="juggluco:meter:v1:$time:$milliMgdl"
}

/** A database identifier alone is not evidence that this is the same history. */
internal data class MeterExportSource(val databaseIdent:Long,val uuidHi:Long,val uuidLo:Long,val epoch:Long)
internal data class MeterExportCoverage(val firstPos:Long,val lastPos:Long,val complete:Boolean)
internal data class MeterExportInventory(
    val unit:Long,val bloodLabel:Long,
    val sourcesCoverage:Map<MeterExportSource,MeterExportCoverage>,
    val memberships:Map<MeterExportReading,Set<MeterExportSource>>
) {
    val readings:List<MeterExportReading> get()=memberships.keys.sortedWith(compareBy({it.time},{it.milliMgdl}))
}

internal object MeterExportPolicy {
    /** Compatibility surface for status counts; null is unavailable, not empty. */
    fun snapshot(raw:LongArray?):List<MeterExportReading>? = inventory(raw)?.readings

    /** Strict schema 2, exactly two genuine, distinct source inventories. */
    fun inventory(raw:LongArray?):MeterExportInventory? {
        if(raw==null||raw.size<4||raw[0]!=2L||raw[1] !in 1L..2L||raw[2]<0L||raw[3]!=2L)return null
        var pos=4
        val sources=linkedMapOf<MeterExportSource,MeterExportCoverage>()
        val identifiers=mutableSetOf<Long>()
        val memberships=linkedMapOf<MeterExportReading,MutableSet<MeterExportSource>>()
        repeat(2) {
            if(raw.size-pos<8)return null
            val source=MeterExportSource(raw[pos],raw[pos+1],raw[pos+2],raw[pos+3])
            val first=raw[pos+4];val last=raw[pos+5];val complete=raw[pos+6];val count=raw[pos+7]
            pos+=8
            if((source.uuidHi==0L&&source.uuidLo==0L)||source.epoch<=0L||
                !identifiers.add(source.databaseIdent)||sources.containsKey(source)||
                first<0L||last<first||complete !in 0L..1L||
                (complete==1L&&first!=0L)||count<0L||count>last-first||count>(raw.size-pos)/2L)return null
            sources[source]=MeterExportCoverage(first,last,complete==1L)
            repeat(count.toInt()) {
                val time=raw[pos];val bits=raw[pos+1];pos+=2
                if(time !in 1L..31556889864403199L||bits !in 0L..0xffffffffL)return null
                val value=java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                val mgdl=if(raw[1]==1L)value*18.0 else value
                // Native units have no per-row provenance; do not infer units from magnitude.
                if(!mgdl.isFinite()||mgdl<=0.0||mgdl>900.0)return null
                val canonical=Math.round(mgdl*1000.0)
                if(canonical==0L)return null
                memberships.getOrPut(MeterExportReading(time,canonical)){linkedSetOf()}.add(source)
            }
        }
        if(pos!=raw.size)return null
        return MeterExportInventory(raw[1],raw[2],sources,memberships)
    }
}
