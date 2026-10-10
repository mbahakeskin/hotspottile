package android.net;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
public class TetheringCallbackStartedParcel implements Parcelable {
    public long supportedTypes; public Network upstreamNetwork; public TetheringConfigurationParcel config;
    public TetherStatesParcel states; public List<TetheredClient> tetheredClients; public int offloadStatus;
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel d, int f) {}
}
