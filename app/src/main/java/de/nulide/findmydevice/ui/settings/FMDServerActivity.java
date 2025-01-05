package de.nulide.findmydevice.ui.settings;

import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeAppBar;
import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeScrollView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.security.PrivateKey;

import de.nulide.findmydevice.R;
import de.nulide.findmydevice.data.EncryptedSettingsRepository;
import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.net.FMDServerApiRepoSpec;
import de.nulide.findmydevice.net.FMDServerApiRepository;
import de.nulide.findmydevice.permissions.NotificationAccessPermission;
import de.nulide.findmydevice.receiver.PushReceiver;
import de.nulide.findmydevice.services.FMDServerLocationUploadService;
import de.nulide.findmydevice.services.FmdBatteryLowService;
import de.nulide.findmydevice.services.FmdServerConnectivityCheckService;
import de.nulide.findmydevice.ui.FmdActivity;
import de.nulide.findmydevice.ui.home.PermissionView;
import de.nulide.findmydevice.utils.CypherUtils;
import de.nulide.findmydevice.utils.UnregisterUtil;
import de.nulide.findmydevice.utils.Utils;

public class FMDServerActivity extends FmdActivity implements CompoundButton.OnCheckedChangeListener, TextWatcher {

    private SettingsRepository settings;
    private FMDServerApiRepository fmdServerRepo;

    private TextView textViewPushHelp;

    private EditText editTextCheckInterval;
    private EditText editTextNotifyAfterTime;
    private EditText editTextFMDServerUpdateTime;

    private CheckBox checkBoxFMDServerGPS;
    private CheckBox checkBoxFMDServerCell;

    private CheckBox checkBoxLowBat;

    private Context context;

    private AlertDialog loadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_f_m_d_server);

        setupEdgeToEdgeAppBar(findViewById(R.id.appBar));
        setupEdgeToEdgeScrollView(findViewById(R.id.scrollView));

        settings = SettingsRepository.Companion.getInstance(this);
        fmdServerRepo = FMDServerApiRepository.Companion.getInstance(new FMDServerApiRepoSpec(this));
        this.context = this;

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

        textViewPushHelp = findViewById(R.id.textPushHelp);

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

        checkBoxFMDServerGPS = findViewById(R.id.checkBoxFMDServerGPS);
        checkBoxFMDServerCell = findViewById(R.id.checkBoxFMDServerCell);
        switch ((Integer) settings.get(Settings.SET_FMDSERVER_LOCATION_TYPE)) {
            case 0:
                checkBoxFMDServerGPS.setChecked(true);
                checkBoxFMDServerCell.setChecked(false);
                break;
            case 1:
                checkBoxFMDServerGPS.setChecked(false);
                checkBoxFMDServerCell.setChecked(true);
                break;
            case 2:
                checkBoxFMDServerGPS.setChecked(true);
                checkBoxFMDServerCell.setChecked(true);
                break;
            case 3:
                checkBoxFMDServerGPS.setChecked(false);
                checkBoxFMDServerCell.setChecked(false);
                break;
        }
        checkBoxFMDServerGPS.setOnCheckedChangeListener(this);
        checkBoxFMDServerCell.setOnCheckedChangeListener(this);

        checkBoxLowBat = findViewById(R.id.checkBoxFMDServerLowBatUpload);
        checkBoxLowBat.setChecked((Boolean) settings.get(Settings.SET_FMD_LOW_BAT_SEND));
        checkBoxLowBat.setOnCheckedChangeListener(this);
        if ((Boolean) settings.get(Settings.SET_FMD_LOW_BAT_SEND)) {
            FmdBatteryLowService.scheduleJobNow(this);
        }

        PermissionView notificationAccessPerm = findViewById(R.id.perm_notification_access);
        notificationAccessPerm.setPermission(new NotificationAccessPermission(), this, true);

        getServerVersion();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PushReceiver.registerWithUnifiedPush(this);

        if (PushReceiver.isRegisteredWithUnifiedPush(this)) {
            textViewPushHelp.setText(R.string.Settings_FMDServer_Push_Description_Available);
        } else {
            textViewPushHelp.setText(R.string.Settings_FMDServer_Push_Description_Missing);
        }

        checkConnection();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == checkBoxFMDServerCell || buttonView == checkBoxFMDServerGPS) {
            if (checkBoxFMDServerGPS.isChecked() && checkBoxFMDServerCell.isChecked()) {
                settings.set(Settings.SET_FMDSERVER_LOCATION_TYPE, 2);
            } else if (checkBoxFMDServerGPS.isChecked()) {
                settings.set(Settings.SET_FMDSERVER_LOCATION_TYPE, 0);
            } else if (checkBoxFMDServerCell.isChecked()) {
                settings.set(Settings.SET_FMDSERVER_LOCATION_TYPE, 1);
            } else {
                settings.set(Settings.SET_FMDSERVER_LOCATION_TYPE, 3);
            }
        } else if (buttonView == checkBoxLowBat) {
            settings.set(Settings.SET_FMD_LOW_BAT_SEND, isChecked);
            if (isChecked) {
                FmdBatteryLowService.scheduleJobNow(this);
            } else {
                FmdBatteryLowService.stopJobNow(this);
            }
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable edited) {
        if (edited == editTextCheckInterval.getText()) {
            String string = edited.toString();
            if (!string.isEmpty()) {
                long value = Long.parseLong(string);
                settings.set(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_INTERVAL_HOURS, value);

                if (value > 0) {
                    FmdServerConnectivityCheckService.scheduleJob(this);
                } else {
                    FmdServerConnectivityCheckService.cancelJob(this);
                }
            }
        } else if (edited == editTextNotifyAfterTime.getText()) {
            String string = edited.toString();
            if (!string.isEmpty()) {
                settings.set(Settings.SET_FMD_SERVER_CONNECTIVITY_CHECK_NOTIFY_AFTER_HOURS, Long.parseLong(string));
            }
        } else if (edited == editTextFMDServerUpdateTime.getText()) {
            String string = edited.toString();
            if (!string.isEmpty()) {
                settings.set(Settings.SET_FMDSERVER_UPDATE_TIME, Integer.parseInt(string));
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
        new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.Settings_FMDServer_Delete_Account))
                .setMessage(R.string.Settings_FMDServer_Alert_DeleteData_Desc)
                .setPositiveButton(getString(R.string.Ok), (dialog, whichButton) -> runDelete())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void onLogoutClicked(View view) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.Settings_FMDServer_Logout_Button))
                .setMessage(R.string.Settings_FMDServer_Logout_Text)
                .setPositiveButton(getString(R.string.Ok), (dialog, whichButton) -> {
                    settings.removeServerAccount();
                    // TODO: API to invalidate access tokens. Maybe combine with session management.
                    EncryptedSettingsRepository encryptedSettingsRepo = EncryptedSettingsRepository.Companion.getInstance(context);
                    encryptedSettingsRepo.setCachedAccessToken("");
                    FMDServerLocationUploadService.cancelJob(this);
                    FmdServerConnectivityCheckService.cancelJob(this);
                    PushReceiver.unregisterWithUnifiedPush(this);
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void onChangePasswordClicked(View view) {
        LayoutInflater inflater = getLayoutInflater();
        final AlertDialog.Builder alert = new MaterialAlertDialogBuilder(this);
        alert.setTitle(context.getString(R.string.Settings_FMDServer_Change_Password_Button));
        View registerLayout = inflater.inflate(R.layout.dialog_password_change, null);
        alert.setView(registerLayout);
        EditText oldPasswordInput = registerLayout.findViewById(R.id.editTextFMDOldPassword);
        EditText passwordInput = registerLayout.findViewById(R.id.editTextFMDPassword);
        EditText passwordInputCheck = registerLayout.findViewById(R.id.editTextFMDPasswordCheck);
        alert.setView(registerLayout);

        alert.setPositiveButton(getString(R.string.Ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String oldPassword = oldPasswordInput.getText().toString();
                String password = passwordInput.getText().toString();
                String passwordCheck = passwordInputCheck.getText().toString();

                if (password.isEmpty() || oldPassword.isEmpty()) {
                    Toast.makeText(context, R.string.pw_change_empty, Toast.LENGTH_LONG).show();
                } else if (!password.equals(passwordCheck)) {
                    Toast.makeText(context, R.string.pw_change_mismatch, Toast.LENGTH_LONG).show();
                } else {
                    runChangePassword(oldPassword, password);
                }
            }
        });
        alert.show();
    }

    private void showLoadingIndicator(Context context) {
        View loadingLayout = getLayoutInflater().inflate(R.layout.dialog_loading, null);
        loadingDialog = new MaterialAlertDialogBuilder(context).setView(loadingLayout).setCancelable(false).create();
        loadingDialog.show();
    }

    private void onOpenUnifiedPushClicked(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://unifiedpush.org/"));
        startActivity(intent);
    }

    private void runChangePassword(String oldPassword, String password) {
        showLoadingIndicator(context);
        // do expensive async crypto and hashing in a background thread (not on the UI thread)
        new Thread(() -> {
            try {
                PrivateKey privKey = CypherUtils.decryptPrivateKeyWithPassword((String) settings.get(Settings.SET_FMD_CRYPT_PRIVKEY), oldPassword);
                if (privKey == null) {
                    Toast.makeText(context, R.string.pw_change_wrong_password, Toast.LENGTH_LONG).show();
                    loadingDialog.cancel();
                    return;
                }
                String newPrivKey = CypherUtils.encryptPrivateKeyWithPassword(privKey, password);
                String hashedPW = CypherUtils.hashPasswordForLogin(password);

                runOnUiThread(() -> {
                    fmdServerRepo.changePassword(hashedPW, newPrivKey,
                            (response -> {
                                loadingDialog.cancel();
                                Toast.makeText(context, R.string.pw_change_success, Toast.LENGTH_LONG).show();
                            }),
                            (error) -> {
                                Toast.makeText(context, R.string.pw_change_network_failed, Toast.LENGTH_LONG).show();
                                loadingDialog.cancel();
                            });
                });
            } catch (Exception bdp) {
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.pw_change_wrong_password, Toast.LENGTH_LONG).show();
                    loadingDialog.cancel();
                });
            }
        }).start();
    }

    private void runDelete() {
        showLoadingIndicator(context);
        FMDServerLocationUploadService.cancelJob(context);
        FmdServerConnectivityCheckService.cancelJob(context);
        PushReceiver.unregisterWithUnifiedPush(context);
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
        fmdServerRepo.getServerVersion(baseUrl, response -> {
            serverVersion.setText(getString(R.string.server_version) + ": " + response);
            serverVersion.setVisibility(View.VISIBLE);
        }, error -> {
            // Silently ignore
            serverVersion.setVisibility(View.GONE);
        });
    }
}
