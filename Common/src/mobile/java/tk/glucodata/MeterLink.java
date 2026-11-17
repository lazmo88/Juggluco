/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

package tk.glucodata;

/**
 * When a Bluetooth glucose meter may hold a connection to this phone, and what a
 * manual fetch achieved. Pure decisions, no Android: GlucoseMeterGatt and
 * BluetoothGlucoseMeter act on them.
 *
 * A meter accepts one phone at a time while it is awake. With ALWAYS (the original
 * behaviour) the phone keeps a background connection request open, so it picks up
 * new readings without the app being open, and it may reach a shared meter before
 * another phone does. With APP_OPEN it only asks while the app is in front and lets
 * go once the app has been left for a while, never in the middle of a transfer.
 */
final class MeterLink {
    private MeterLink() {}

    enum Mode { ALWAYS, APP_OPEN }

    /** A records request the meter does not answer stops counting as a transfer. */
    static final long TRANSFER_TIMEOUT_MS=60_000L;
    /** The app must be out of sight this long before an APP_OPEN meter is let go:
     *  rotation, a permission dialog or a quick switch is not leaving. */
    static final long RELEASE_GRACE_MS=15_000L;

    static boolean mayConnect(Mode mode,boolean foreground) {
        return mode!=Mode.APP_OPEN||foreground;
    }

    /** From the records request until the meter's answer, bounded by TRANSFER_TIMEOUT_MS. */
    static boolean transferring(long requestedAt,long answeredAt,long now) {
        return requestedAt>0L&&answeredAt<requestedAt&&now-requestedAt>=0L&&now-requestedAt<TRANSFER_TIMEOUT_MS;
    }

    enum Release { KEEP, WAIT, CLOSE }
    /** Whether to let go of the meter now. A transfer in flight is never cut. */
    static Release release(Mode mode,boolean foreground,boolean transferring) {
        if(mayConnect(mode,foreground))
            return Release.KEEP;
        return transferring?Release.WAIT:Release.CLOSE;
    }

    enum Fetch {
        /** Connected and idle: records were requested again. */
        REQUEST,
        /** Already connected and busy (pairing, discovery or a transfer); its answer counts. */
        BUSY,
        /** Not connected: one direct connection attempt was started. */
        CONNECT,
        /** Not allowed now, or no meter address: nothing was done. */
        SKIPPED
    }
    /**
     * @param ready connected, services found and the records point known
     * @param connected the link is up (may still be pairing or discovering)
     */
    static Fetch fetch(boolean ready,boolean connected,boolean transferring,boolean allowed,boolean hasAddress) {
        if(ready)
            return transferring?Fetch.BUSY:Fetch.REQUEST;
        if(connected)
            return Fetch.BUSY;
        if(!allowed||!hasAddress)
            return Fetch.SKIPPED;
        return Fetch.CONNECT;
    }

    /** Best first: what a pull tells the user. */
    enum Outcome { NEW, NONE, SENDING, UNREACHED }
    /** One meter, for a fetch started at since. Times from System.currentTimeMillis(). */
    static Outcome outcome(long since,boolean connectedNow,long connectedAt,long answeredAt,boolean hadData) {
        if(answeredAt>=since)
            return hadData?Outcome.NEW:Outcome.NONE;
        if(connectedNow||connectedAt>=since)
            return Outcome.SENDING;
        return Outcome.UNREACHED;
    }
    static boolean settled(Outcome outcome) {
        return outcome==Outcome.NEW||outcome==Outcome.NONE;
    }
    /** Several meters: the best result wins; null for none. */
    static Outcome best(Outcome a,Outcome b) {
        if(a==null) return b;
        if(b==null) return a;
        return a.ordinal()<=b.ordinal()?a:b;
    }

    static Mode parse(String stored) {
        return "app_open".equals(stored)?Mode.APP_OPEN:Mode.ALWAYS;
    }
    static String store(Mode mode) {
        return mode==Mode.APP_OPEN?"app_open":"always";
    }
}
