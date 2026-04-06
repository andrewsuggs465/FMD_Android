package de.nulide.findmydevice.ui.settings;

import static org.unifiedpush.android.connector.ConstantsKt.INSTANCE_DEFAULT;
import static de.nulide.findmydevice.services.UnifiedPushServiceKt.unregisterWithUnifiedPush;
import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeAppBar;
import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeScrollView;
import static de.nulide.findmydevice.utils.CypherUtils.MIN_PASSWORD_LENGTH;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.ResolvedDistributor;

import java.util.Objects;

import de.nulide.findmydevice.R;
import de.nulide.findmydevice.data.BackgroundLocationType;
import de.nulide.findmydevice.data.EncryptedSettingsRepository;
import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.net.FmdServerApiService;
import de.nulide.findmydevice.net.FmdServerRepository;
import de.nulide.findmydevice.net.ServerCommandDownloader;
import de.nulide.findmydevice.services.FmdBatteryLowService;
import de.nulide.findmydevice.services.ServerConnectivityCheckService;
import de.nulide.findmydevice.services.ServerLocationUploadService;
import de.nulide.findmydevice.ui.FmdActivity;
import de.nulide.findmydevice.utils.FmdLogKt;
import de.nulide.findmydevice.utils.UnregisterUtil;
import de.nulide.findmydevice.utils.Utils;
import de.nulide.findmydevice.warnings.PushWarningsKt;
import kotlin.Unit;

public class FMDServerActivity extends FmdActivity implements CompoundButton.OnCheckedChangeListener, TextWatcher {

    private static final String TAG = FMDServerActivity.class.getSimpleName();

    public static final String EXTRA_NEW_ACCOUNT = "EXTRA_NEW_ACCOUNT";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SettingsRepository settings;
    private FmdServerApiService fmdServerRepo;

    private EditText editTextCheckInterval;
    private EditText editTextNotifyAfterTime;
    private EditText editTextFMDServerUpdateTime;

    private CheckBox checkBoxFMDServerFused;
    private CheckBox checkBoxFMDServerGPS;
    private CheckBox checkBoxFMDServerCell;

    private CheckBox checkBoxLowBat;

    private AlertDialog loadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_f_m_d_server);

        setupEdgeToEdgeAppBar(findViewById(R.id.appBar));
        setupEdgeToEdgeScrollView(findViewById(R.id.scrollView));

        settings = SettingsRepository.Companion.getInstance(this);
        fmdServerRepo = new FmdServerRepository(this).getApiService();

        TextView textViewServerUrl = findViewById(R.id.textViewServerUrl);
        TextView textViewUserId = findViewById(R.id.textViewUserId);
        textViewServerUrl.setText((String) settings.get(Settings.SET_FMDSERVER_URL));
        textViewUserId.setText((String) settings.get(Settings.SET_FMDSERVER_ID));

        findViewById(R.id.buttonOpenWebClient).setOnClickListener(this::onOpenWebClientClicked);
        findViewById(R.id.buttonCopyServerUrl).setOnClickListener(this::onCopyServerUrlClicked);
        findViewById(R.id.buttonCopyUserId).setOnClickListener(this::onCopyUserIdClicked);

        findViewById(R.id.buttonChangePassword).setOnClickListener(this::onChangePasswordClicked);
        findViewById(R.id.buttonLogout).setOnClickListener(this::onLogoutClicked);
        findViewById(R.id.buttonDeleteData).setOnClickListener(this::onDeleteClicked);

        findViewById(R.id.buttonOpenPushDistributor).setOnClickListener(this::onOpenPushDistributorClicked);
        findViewById(R.id.buttonCopyPushDistributor).setOnClickListener(this::onCopyPushDistributorClicked);
        findViewById(R.id.buttonCopyPushUrl).setOnClickListener(this::onCopyPushUrlClicked);
        findViewById(R.id.buttonInstallSunup).setOnClickListener(this::onInstallSunupClicked);
        findViewById(R.id.buttonRegisterPush).setOnClickListener(this::onRegisterPushClicked);
        findViewById(R.id.buttonOpenUnifiedPush).setOnClickListener(this::onOpenUnifiedPushClicked);

        editTextCheckInterval = findViewById(R.id.editTextCheckInterval);
        editTextCheckInterval.setText(settings.get(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_INTERVAL_HOURS).toString());
        editTextCheckInterval.addTextChangedListener(this);

        editTextNotifyAfterTime = findViewById(R.id.editTextNotifyAfterTime);
        editTextNotifyAfterTime.setText(settings.get(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_NOTIFY_AFTER_HOURS).toString());
        editTextNotifyAfterTime.addTextChangedListener(this);

        editTextFMDServerUpdateTime = findViewById(R.id.editTextFMDServerUpdateTime);
        editTextFMDServerUpdateTime.setText(settings.get(Settings.SET_FMDSERVER_UPDATE_TIME).toString());
        editTextFMDServerUpdateTime.addTextChangedListener(this);

        checkBoxFMDServerFused = findViewById(R.id.checkBoxFMDServerFused);
        checkBoxFMDServerGPS = findViewById(R.id.checkBoxFMDServerGPS);
        checkBoxFMDServerCell = findViewById(R.id.checkBoxFMDServerCell);

        int locTypeInt = (int) settings.get(Settings.SET_FMDSERVER_LOCATION_TYPE);
        BackgroundLocationType locType = new BackgroundLocationType(locTypeInt);

        checkBoxFMDServerFused.setChecked(locType.getFused());
        checkBoxFMDServerGPS.setChecked(locType.getGps());
        checkBoxFMDServerCell.setChecked(locType.getCell());

        checkBoxFMDServerFused.setOnCheckedChangeListener(this);
        checkBoxFMDServerGPS.setOnCheckedChangeListener(this);
        checkBoxFMDServerCell.setOnCheckedChangeListener(this);

        checkBoxLowBat = findViewById(R.id.checkBoxFMDServerLowBatUpload);
        checkBoxLowBat.setChecked((Boolean) settings.get(Settings.SET_FMD_LOW_BAT_SEND));
        checkBoxLowBat.setOnCheckedChangeListener(this);
        if ((Boolean) settings.get(Settings.SET_FMD_LOW_BAT_SEND)) {
            FmdBatteryLowService.scheduleJobNow(this);
        }

        getServerVersion();
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkConnection();
        updatePushSection();
        new ServerCommandDownloader(this).download();

        // If the user comes from the registration/login screen,
        // try to register with UnifiedPush automatically, to complete the initial setup.
        Intent intent = getIntent();
        if (intent != null) {
            boolean isNewAccount = intent.getBooleanExtra(EXTRA_NEW_ACCOUNT, false);
            if (isNewAccount) {
                registerWithUnifiedPush();

                // Avoid re-consuming this intent
                setIntent(null);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == checkBoxFMDServerFused
                || buttonView == checkBoxFMDServerGPS
                || buttonView == checkBoxFMDServerCell) {

            BackgroundLocationType locType = BackgroundLocationType.Companion.fromEmpty();
            locType.setFused(checkBoxFMDServerFused.isChecked());
            locType.setGps(checkBoxFMDServerGPS.isChecked());
            locType.setCell(checkBoxFMDServerCell.isChecked());
            settings.set(Settings.SET_FMDSERVER_LOCATION_TYPE, locType.encode());

            if (!locType.isEmpty()) {
                ServerLocationUploadService.scheduleRecurring(this);
            } else {
                ServerLocationUploadService.cancelJob(this);
            }
        } else if (buttonView == checkBoxLowBat) {
            settings.set(Settings.SET_FMD_LOW_BAT_SEND, isChecked);
            if (isChecked) {
                FmdBatteryLowService.scheduleJobNow(this);
            } else {
                FmdBatteryLowService.cancelJob(this);
            }
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // unused
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // unused
    }

    @Override
    public void afterTextChanged(Editable edited) {
        String string = edited.toString();
        if (string.isEmpty()) {
            return;
        }

        long value;
        try {
            // The EditText's inputType shouldn't allow non-numbers, but catch the exception anyway.
            value = Long.parseLong(string);
        } catch (NumberFormatException e) {
            return;
        }

        if (edited == editTextCheckInterval.getText()) {
            settings.set(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_INTERVAL_HOURS, value);

            if (value > 0) {
                ServerConnectivityCheckService.scheduleJob(this);
            } else {
                ServerConnectivityCheckService.cancelJob(this);
            }
        } else if (edited == editTextNotifyAfterTime.getText()) {
            settings.set(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_NOTIFY_AFTER_HOURS, value);
        } else if (edited == editTextFMDServerUpdateTime.getText()) {
            int interval = (int) value;
            settings.set(Settings.SET_FMDSERVER_UPDATE_TIME, interval);

            // Reschedule with new interval
            if (checkBoxFMDServerGPS.isChecked() || checkBoxFMDServerCell.isChecked()) {
                ServerLocationUploadService.scheduleJob(this, interval);
            }
        }
    }

    private void onOpenWebClientClicked(View view) {
        String url = (String) settings.get(Settings.SET_FMDSERVER_URL);
        Utils.openUrl(this, url);
    }

    private void onCopyServerUrlClicked(View view) {
        String label = getString(R.string.Settings_FMD_Server_Server_URL).replace(":", "");
        String text = (String) settings.get(Settings.SET_FMDSERVER_URL);
        Utils.copyToClipboard(this, label, text);
    }

    private void onCopyUserIdClicked(View view) {
        String label = getString(R.string.Settings_FMD_Server_User_ID).replace(":", "");
        String text = (String) settings.get(Settings.SET_FMDSERVER_ID);
        Utils.copyToClipboard(this, label, text);
    }

    private void onDeleteClicked(View view) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.Settings_FMDServer_Delete_Account))
                .setMessage(R.string.Settings_FMDServer_Alert_DeleteData_Desc)
                .setPositiveButton(getString(R.string.Ok), (dialog, whichButton) -> runDelete())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void onLogoutClicked(View view) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.Settings_FMDServer_Logout_Button))
                .setMessage(R.string.Settings_FMDServer_Logout_Text)
                .setPositiveButton(getString(R.string.Ok), (dialog, whichButton) -> {
                    settings.removeServerAccount(false);
                    // TODO: API to invalidate access tokens. Maybe combine with session management.
                    EncryptedSettingsRepository encryptedSettingsRepo = EncryptedSettingsRepository.Companion.getInstance(this);
                    encryptedSettingsRepo.setCachedAccessToken("");
                    ServerLocationUploadService.cancelJob(this);
                    ServerConnectivityCheckService.cancelJob(this);
                    unregisterWithUnifiedPush(this);
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void onChangePasswordClicked(View view) {
        LayoutInflater inflater = getLayoutInflater();
        final AlertDialog.Builder alert = new MaterialAlertDialogBuilder(this);
        alert.setTitle(getString(R.string.Settings_FMDServer_Change_Password_Button));
        alert.setCancelable(false);

        View registerLayout = inflater.inflate(R.layout.dialog_password_change, null);
        alert.setView(registerLayout);

        EditText oldPasswordInput = registerLayout.findViewById(R.id.editTextFMDOldPassword);
        EditText passwordInput = registerLayout.findViewById(R.id.editTextPassword);
        alert.setView(registerLayout);

        alert.setPositiveButton(getString(R.string.Ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String oldPassword = oldPasswordInput.getText().toString();
                String password = passwordInput.getText().toString();

                if (password.isEmpty() || oldPassword.isEmpty()) {
                    Toast.makeText(view.getContext(), R.string.pw_change_empty, Toast.LENGTH_LONG).show();
                } else if (password.length() < MIN_PASSWORD_LENGTH) {
                    Toast.makeText(view.getContext(), R.string.password_min_length, Toast.LENGTH_LONG).show();
                } else {
                    runChangePassword(oldPassword, password);
                }
            }
        });

        alert.setNegativeButton(R.string.cancel, (dialog, whichButton) -> {
        });
        alert.show();
    }

    private void showLoadingIndicator(Context context) {
        View loadingLayout = getLayoutInflater().inflate(R.layout.dialog_loading, null);
        loadingDialog = new MaterialAlertDialogBuilder(context).setView(loadingLayout).setCancelable(false).create();
        loadingDialog.show();
    }

    private void onOpenPushDistributorClicked(View view) {
        String packageName = UnifiedPush.getAckDistributor(this);
        Utils.openApp(this, packageName);
    }

    private void onCopyPushDistributorClicked(View view) {
        String text = UnifiedPush.getAckDistributor(this);
        Utils.copyToClipboard(this, "Push Distributor", text);
    }

    private void onCopyPushUrlClicked(View view) {
        String text = (String) settings.get(Settings.SET_FMDSERVER_PUSH_URL);
        Utils.copyToClipboard(this, "Push URL", text);
    }

    private void onOpenUnifiedPushClicked(View view) {
        Utils.openUrl(this, "https://fmd-foss.org/docs/fmd-android/push");
    }

    private void onInstallSunupClicked(View view) {
        Utils.openUrl(this, "https://f-droid.org/en/packages/org.unifiedpush.distributor.sunup");
    }

    private void runChangePassword(String oldPassword, String newPassword) {
        showLoadingIndicator(this);

        // do expensive async crypto and hashing in a background thread (not on the UI thread)
        new Thread(() -> {
            fmdServerRepo.changePassword(oldPassword, newPassword,
                    (response -> runOnUiThread(() -> {
                        loadingDialog.cancel();
                        Toast.makeText(this, R.string.pw_change_success, Toast.LENGTH_LONG).show();
                    })),
                    (error -> runOnUiThread(() -> {
                        loadingDialog.cancel();
                        if (Objects.equals(error.getMessage(), "WRONG_PASSWORD")) {
                            Toast.makeText(this, R.string.pw_change_wrong_password, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, R.string.pw_change_network_failed, Toast.LENGTH_LONG).show();
                        }
                    }))
            );
        }).start();
    }

    private void runDelete() {
        Context context = this;
        showLoadingIndicator(context);
        ServerLocationUploadService.cancelJob(context);
        ServerConnectivityCheckService.cancelJob(context);
        unregisterWithUnifiedPush(context);
        fmdServerRepo.unregister(
                response -> {
                    loadingDialog.cancel();
                    Toast.makeText(context, R.string.Settings_FMDServer_Unregister_Success, Toast.LENGTH_LONG).show();
                    finish();
                }, error -> {
                    loadingDialog.cancel();
                    UnregisterUtil.showUnregisterFailedDialog(context, error, this::finish);
                }
        );
    }

    private void checkConnection() {
        TextView textViewConnectionStatus = findViewById(R.id.textViewConnectionStatus);

        fmdServerRepo.checkConnection(
                response -> {
                    settings.set(
                            Settings.SET_FMD_SERVER_LAST_CONNECTIVITY_UNIX_TIME,
                            System.currentTimeMillis()
                    );

                    textViewConnectionStatus.setText(R.string.Settings_FMD_Server_Connection_Status_Success);
                    textViewConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.md_theme_primary));
                    textViewConnectionStatus.setOnClickListener(v -> {
                    });
                },
                error -> {
                    textViewConnectionStatus.setText(error.toString());
                    textViewConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error));
                    textViewConnectionStatus.setOnClickListener(v -> {
                        Utils.copyToClipboard(this, "", error.toString());
                    });
                }
        );
    }

    @SuppressLint("SetTextI18n")
    private void getServerVersion() {
        TextView serverVersion = findViewById(R.id.serverVersion);

        String baseUrl = (String) settings.get(Settings.SET_FMDSERVER_URL);
        new FmdServerRepository(this).getServerVersion(baseUrl, response -> {
            serverVersion.setText(getString(R.string.server_version) + ": " + response);
            serverVersion.setVisibility(View.VISIBLE);
        }, error -> {
            // Silently ignore
            serverVersion.setVisibility(View.GONE);
        });
    }

    private void updatePushSection() {
        LinearLayout sectionPushDistributor = findViewById(R.id.sectionPushDistributor);
        TextView textPushDistributor = findViewById(R.id.textPushDistributor);
        LinearLayout sectionPushUrl = findViewById(R.id.sectionPushUrl);
        TextView textPushUrl = findViewById(R.id.textPushUrl);
        Button buttonRegister = findViewById(R.id.buttonRegisterPush);

        TextView textInfoSunup = findViewById(R.id.textInfoSunup);
        Button buttonInstallSunup = findViewById(R.id.buttonInstallSunup);

        String distributor = UnifiedPush.getAckDistributor(this);
        String url = (String) settings.get(Settings.SET_FMDSERVER_PUSH_URL);

        if (distributor != null && !distributor.isEmpty() && !url.isEmpty()) {
            sectionPushDistributor.setVisibility(View.VISIBLE);
            textPushDistributor.setText(getString(R.string.Settings_FMDServer_Push_Distributor, distributor));

            sectionPushUrl.setVisibility(View.VISIBLE);
            textPushUrl.setText(getString(R.string.Settings_FMDServer_Push_Url, url));

            textInfoSunup.setVisibility(View.GONE);
            buttonInstallSunup.setVisibility(View.GONE);

            buttonRegister.setText(R.string.Settings_FMDServer_Push_Register_Again);
        } else {
            sectionPushDistributor.setVisibility(View.GONE);
            sectionPushUrl.setVisibility(View.GONE);

            textInfoSunup.setVisibility(View.VISIBLE);
            buttonInstallSunup.setVisibility(View.VISIBLE);

            buttonRegister.setText(R.string.Settings_FMDServer_Push_Register);
        }
    }

    private void onRegisterPushClicked(View view) {
        // Force re-registration, to refresh the push URL
        unregisterWithUnifiedPush(view.getContext());
        registerWithUnifiedPush();
    }

    private void registerWithUnifiedPush() {
        Context context = this;
        ResolvedDistributor res = UnifiedPush.resolveDefaultDistributor(context);

        switch (res) {
            case ResolvedDistributor.Found found:
                // The user doesn't have to interact. Will be the case most of the time,
                // if the user has a default or a single distributor installed
                UnifiedPush.saveDistributor(context, found.getPackageName());
                UnifiedPush.register(context, INSTANCE_DEFAULT, null, null);
                handler.postDelayed(this::updatePushSection, 1500L);
                break;

            case ResolvedDistributor.ToSelect ignored:
                // Always show this dialog, so that the user knows what to do in the OS picker
                PushWarningsKt.showDialogMultipleUnifiedPushDistributorApps(context, () -> {
                    registerWithUnifiedPushMultipleDistributors();
                    return Unit.INSTANCE;
                });
                break;

            case ResolvedDistributor.NoneAvailable ignored:
                FmdLogKt.log(context).w(TAG, "No UnifiedPush distributor app found.");
                PushWarningsKt.showDialogMissingUnifiedPush(context, null);
                break;

            default:
                FmdLogKt.log(context).e(TAG, "Unknown ResolvedDistributor case: " + res);
                break;
        }
    }

    private void registerWithUnifiedPushMultipleDistributors() {
        UnifiedPush.tryPickDistributor(this, (success) -> {
            if (success) {
                // No need to save the distributor
                UnifiedPush.register(this, INSTANCE_DEFAULT, null, null);
                handler.postDelayed(this::updatePushSection, 1500L);
            }
            return Unit.INSTANCE;
        });
    }

}
