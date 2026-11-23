package net.programmierecke.radiodroid2;

import android.content.Context;
import net.programmierecke.radiodroid2.utils.AppLog;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

public class GoogleProviderHelper{
    public static void use(Context ctx){
        try {
            AppLog.i("HLP","Try to install google helper for higher TLS support..");
            ProviderInstaller.installIfNeeded(ctx);
            AppLog.i("HLP","Google helper was installed OK.");
        } catch (GooglePlayServicesRepairableException e) {
            AppLog.e("HLP","Google helper was not installed because services not repairable!");
            e.printStackTrace();
        } catch (GooglePlayServicesNotAvailableException e) {
            AppLog.e("HLP","Google helper was not installed because services not available!");
            e.printStackTrace();
        }
    }
}