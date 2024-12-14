package de.nulide.findmydevice.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.Locale;

import de.nulide.findmydevice.commands.CommandHandler;
import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.transports.SmsTransport;
import de.nulide.findmydevice.transports.Transport;
import de.nulide.findmydevice.utils.FmdLogKt;


public class FMDSMSService extends FmdJobService {

    private static final String TAG = FMDSMSService.class.getSimpleName();

    private static final int JOB_ID = 107;

    private static final String DESTINATION = "dest";
    private static final String MESSAGE = "msg";
    private static final String SUBSCRIPTION_ID = "subscription-id";

    public static void scheduleJob(Context context, String destination, int subscriptionId, String message) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString(DESTINATION, destination);
        bundle.putInt(SUBSCRIPTION_ID, subscriptionId);
        bundle.putString(MESSAGE, message);

        ComponentName serviceComponent = new ComponentName(context, FMDSMSService.class);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, serviceComponent)
                .setExtras(bundle);
        builder.setMinimumLatency(0);
        builder.setOverrideDeadline(0);

        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(builder.build());
    }

    public boolean onStartJob(JobParameters params) {
        super.onStartJob(params);

        String phoneNumber = params.getExtras().getString(DESTINATION);
        int subscriptionId = params.getExtras().getInt(SUBSCRIPTION_ID);
        String msg = params.getExtras().getString(MESSAGE);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            FmdLogKt.log(this).i(TAG, "Cannot handle SMS: phoneNumber is empty!");
            return false;
        }
        if (msg == null || msg.isEmpty()) {
            FmdLogKt.log(this).i(TAG, "Cannot handle SMS: msg is empty!");
            return false;
        }

        // Early sanity check + abort
        SettingsRepository settings = SettingsRepository.Companion.getInstance(this);
        String fmdTriggerWord = ((String) settings.get(Settings.SET_FMD_COMMAND)).toLowerCase(Locale.ROOT);
        if (!msg.toLowerCase(Locale.ROOT).startsWith(fmdTriggerWord)) {
            return false;
        }

        Transport<String> transport = new SmsTransport(this, phoneNumber, subscriptionId);
        CommandHandler<String> commandHandler = new CommandHandler<>(transport, this.getCoroutineScope(), this);
        commandHandler.execute(this, msg);

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        super.onStopJob(params);
        return false;
    }
}
