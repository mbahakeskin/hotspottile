package android.net;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
public interface IIntResultListener extends IInterface {
    abstract class Stub extends Binder implements IIntResultListener {
        @Override public IBinder asBinder() { return this; }
    }
    void onResult(int resultCode);
}
