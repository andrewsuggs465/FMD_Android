package de.nulide.findmydevice.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import de.nulide.findmydevice.R;
import de.nulide.findmydevice.commands.CommandHandler;
import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.net.FMDServerApiRepoSpec;
import de.nulide.findmydevice.net.FMDServerApiRepository;
import de.nulide.findmydevice.transports.FmdServerTransport;
import de.nulide.findmydevice.transports.Transport;
import de.nulide.findmydevice.utils.FmdLogKt;
import de.nulide.findmydevice.utils.Notifications;
import kotlin.Unit;

/**
 * Downloads the latest command and executes it
 */
public class FMDServerCommandDownloadService extends FmdJobService {

    private final String TAG = FMDServerCommandDownloadService.class.getSimpleName();

    private static final int JOB_ID = 109;
    private SettingsRepository settingsRepo;

    @Override
    public boolean onStartJob(JobParameters params) {
        super.onStartJob(params);

        settingsRepo = SettingsRepository.Companion.getInstance(this);

        if (!settingsRepo.serverAccountExists()) {
            return false;
        }

        FmdLogKt.log(this).d(TAG, "Downloading remote command");
        FMDServerApiRepository fmdServerRepo = FMDServerApiRepository.Companion.getInstance(new FMDServerApiRepoSpec(this));
        fmdServerRepo.getCommand(this::onResponse, Throwable::printStackTrace);

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        super.onStopJob(params);
        return true;
    }

    public static void scheduleJobNow(Context context) {
        ComponentName serviceComponent = new ComponentName(context, FMDServerCommandDownloadService.class);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, serviceComponent);
        builder.setMinimumLatency(0);
        builder.setOverrideDeadline(1000);
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(builder.build());
    }

    private void onResponse(String remoteCommand) {
        FmdLogKt.log(this).i(TAG, "Received remote command '" + remoteCommand + "'");
        if (remoteCommand.isEmpty()) {
            return;
        }
        if (remoteCommand.startsWith("423")) {
            String account = (String) settingsRepo.get(Settings.SET_FMDSERVER_ID);
            String msg = getString(R.string.server_login_attempts_text, account);
            FmdLogKt.log(this).w(TAG, msg);
            Notifications.notify(
                    this,
                    getString(R.string.server_login_attempts_title),
                    msg,
                    Notifications.CHANNEL_SERVER
            );
            return;
        }
        String fullCommand = settingsRepo.get(Settings.SET_FMD_COMMAND) + " " + remoteCommand;

        Transport<Unit> transport = new FmdServerTransport(this);
        CommandHandler<Unit> commandHandler = new CommandHandler<>(transport, this.getCoroutineScope(), this);
        commandHandler.execute(this, fullCommand);
    }
}
