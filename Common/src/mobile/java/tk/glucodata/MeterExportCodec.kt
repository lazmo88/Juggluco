// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

internal object MeterExportCodec {
    fun encode(state:MeterExportState):String = buildString {
        append("2|${state.unit}|${state.bloodLabel}")
        state.owned.sorted().forEach{append("\nO|$it")}
        state.intent.forEach{append("\nP|${it.time}|${it.milliMgdl}")}
        state.memberships.toSortedMap().forEach { (id,sources) ->
            require(sources.isNotEmpty()) {"Empty meter export provenance"}
            sources.sortedWith(compareBy({it.databaseIdent},{it.uuidHi},{it.uuidLo},{it.epoch})).forEach {
                append("\nM|$id|${it.databaseIdent}|${it.uuidHi}|${it.uuidLo}|${it.epoch}")
            }
        }
    }.also { decode(it) } // Refuse inconsistent in-memory ownership/provenance too.

    fun decode(text:String):MeterExportState {
        val lines=text.split('\n');val header=lines.first().split('|')
        require(header.size==3&&header[0] in setOf("1","2")) {"Invalid meter export ledger schema"}
        val unit=header[1].toLong();val label=header[2].toLong()
        require(unit in 1L..2L&&label>=0) {"Invalid meter export ledger binding"}
        val owned=linkedSetOf<String>();val intent=linkedSetOf<MeterExportReading>()
        val memberships=linkedMapOf<String,MutableSet<MeterExportSource>>()
        fun reading(time:String,value:String):MeterExportReading {
            val r=MeterExportReading(time.toLong(),value.toLong())
            require(r.time in 1L..31556889864403199L&&r.milliMgdl in 1L..900000L) {"Invalid meter export ledger reading"}
            return r
        }
        fun ownedId(id:String):String {
            val parts=id.split(':')
            require(parts.size==5&&parts.take(3)==listOf("juggluco","meter","v1")) {"Unowned meter export ledger ID"}
            require(reading(parts[3],parts[4]).clientId==id) {"Noncanonical meter export ledger ID"}
            return id
        }
        for(line in lines.drop(1)) {
            val fields=line.split('|')
            when {
                fields.size==2&&fields[0]=="O" -> require(owned.add(ownedId(fields[1]))) {"Duplicate meter export ledger ID"}
                fields.size==3&&fields[0]=="P" -> require(intent.add(reading(fields[1],fields[2]))) {"Duplicate meter export intent"}
                fields.size==6&&fields[0]=="M"&&header[0]=="2" -> {
                    val id=ownedId(fields[1])
                    val source=MeterExportSource(fields[2].toLong(),fields[3].toLong(),fields[4].toLong(),fields[5].toLong())
                    require((source.uuidHi!=0L||source.uuidLo!=0L)&&source.epoch>0L) {"Invalid meter export provenance"}
                    require(memberships.getOrPut(id){linkedSetOf()}.add(source)) {"Duplicate meter export provenance"}
                }
                else -> throw IllegalArgumentException("Invalid meter export ledger entry")
            }
        }
        require((memberships.keys-(owned+intent.map{it.clientId})).isEmpty()) {"Orphan meter export provenance"}
        // v1 O/P rows remain replayable and idempotent, but their missing proof is permanent.
        return MeterExportState(unit,label,owned,intent.toList(),memberships)
    }
}
