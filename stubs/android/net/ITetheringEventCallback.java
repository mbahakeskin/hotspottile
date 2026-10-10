package android.net;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import java.util.List;
/** Derleme-zamani stub. Calisma zamaninda sistemin gercek sinifi kullanilir (bootclasspath once yuklenir). */
public interface ITetheringEventCallback extends IInterface {
    abstract class Stub extends Binder implements ITetheringEventCallback {
        public static ITetheringEventCallback asInterface(IBinder b) { throw new RuntimeException("stub"); }
        @Override public IBinder asBinder() { return this; }
    }
    void onCallbackStarted(TetheringCallbackStartedParcel parcel);
    void onCallbackStopped(int errorCode);
    void onUpstreamChanged(Network network);
    void onConfigurationChanged(TetheringConfigurationParcel config);
    void onTetherStatesChanged(TetherStatesParcel states);
    void onTetherClientsChanged(List<TetheredClient> clients);
    void onOffloadStatusChanged(int status);
    void onSupportedTetheringTypes(long supportedBitmap);
}
