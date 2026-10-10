package android.net;
import android.os.Parcel;
import android.os.Parcelable;
import android.net.MacAddress;
import java.util.List;
public final class TetheredClient implements Parcelable {
    public MacAddress getMacAddress() { return null; }
    public List<AddressInfo> getAddresses() { return null; }
    public int getTetheringType() { return 0; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel d, int f) {}
    public static final class AddressInfo implements Parcelable {
        public LinkAddress getAddress() { return null; }
        public String getHostname() { return null; }
        public long getExpirationTime() { return 0; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel d, int f) {}
    }
}
