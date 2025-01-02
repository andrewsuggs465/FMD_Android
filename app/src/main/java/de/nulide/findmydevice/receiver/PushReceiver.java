package de.nulide.findmydevice.receiver;

import android.content.Context;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.unifiedpush.android.connector.ConstantsKt;
import org.unifiedpush.android.connector.MessagingReceiver;
import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;

import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.net.FMDServerApiRepoSpec;
import de.nulide.findmydevice.net.FMDServerApiRepository;
import de.nulide.findmydevice.services.FMDServerCommandDownloadService;
import de.nulide.findmydevice.utils.FmdLogKt;


public class PushReceiver extends MessagingReceiver {

    private final String TAG = PushReceiver.class.getSimpleName();

    public PushReceiver() {
        super();
    }

    @Override
    public void onMessage(@NonNull Context context, @NonNull byte[] message, @NonNull String instance) {
        FmdLogKt.log(context).i(TAG, "Received push message");
        FMDServerCommandDownloadService.scheduleJobNow(context);
    }

    @Override
    public void onNewEndpoint(@NonNull Context context, @NotNull String endpoint, @NotNull String instance) {
        SettingsRepository settings = SettingsRepository.Companion.getInstance(context);
        settings.set(Settings.SET_FMDSERVER_PUSH_URL, endpoint);

        FMDServerApiRepository repo = FMDServerApiRepository.Companion.getInstance(new FMDServerApiRepoSpec(context));
        repo.registerPushEndpoint(endpoint, (error) -> {
            error.printStackTrace();
        });
    }

    @Override
    public void onRegistrationFailed(@NonNull Context context, @NotNull String s) {
        // do nothing
    }

    @Override
    public void onUnregistered(@NonNull Context context, @NotNull String s) {
        SettingsRepository settings = SettingsRepository.Companion.getInstance(context);
        settings.set(Settings.SET_FMDSERVER_PUSH_URL, "");

        FMDServerApiRepository repo = FMDServerApiRepository.Companion.getInstance(new FMDServerApiRepoSpec(context));
        repo.registerPushEndpoint("", (error) -> {
            error.printStackTrace();
        });
    }

    public static void registerWithUnifiedPush(Context context) {
        if (isUnifiedPushAvailable(context)) {
            UnifiedPush.registerAppWithDialog(context, ConstantsKt.INSTANCE_DEFAULT, "", new ArrayList<>(), "");
        }
    }

    public static void unregisterWithUnifiedPush(Context context) {
        if (isRegisteredWithUnifiedPush(context)) {
            UnifiedPush.unregisterApp(context, ConstantsKt.INSTANCE_DEFAULT);
        }
        // ensure that the state is cleared
        new PushReceiver().onUnregistered(context, "");
    }

    public static boolean isRegisteredWithUnifiedPush(Context context) {
        return !UnifiedPush.getDistributor(context).isEmpty();
    }

    public static boolean isUnifiedPushAvailable(Context context) {
        return UnifiedPush.getDistributors(context, new ArrayList<>()).size() > 0;
    }
}
