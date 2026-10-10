package android.net;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
/** Derleme-zamani stub (Android 17 imzalari). */
public interface ITetheringConnector extends IInterface {
    abstract class Stub extends Binder implements ITetheringConnector {
        public static ITetheringConnector asInterface(IBinder b) { throw new RuntimeException("stub"); }
        @Override public IBinder asBinder() { return this; }
    }
    void registerTetheringEventCallback(ITetheringEventCallback callback, String callerPkg);
    void unregisterTetheringEventCallback(ITetheringEventCallback callback, String callerPkg);
    void stopTethering(int type, String callerPkg, String attributionTag, IIntResultListener receiver);
    void startTethering(TetheringRequestParcel request, String callerPkg, String attributionTag, IIntResultListener receiver);
    void stopAllTethering(String callerPkg, String attributionTag, IIntResultListener receiver);
}
