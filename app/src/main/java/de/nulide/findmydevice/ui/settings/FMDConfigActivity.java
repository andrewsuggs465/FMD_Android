package de.nulide.findmydevice.ui.settings;

import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeAppBar;
import static de.nulide.findmydevice.ui.UiUtil.setupEdgeToEdgeScrollView;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import de.nulide.findmydevice.R;
import de.nulide.findmydevice.data.EncryptedSettingsRepository;
import de.nulide.findmydevice.data.Settings;
import de.nulide.findmydevice.data.SettingsRepository;
import de.nulide.findmydevice.ui.FmdActivity;
import de.nulide.findmydevice.ui.common.PasswordSetDialog;
import kotlin.Unit;

public class FMDConfigActivity extends FmdActivity implements CompoundButton.OnCheckedChangeListener, TextWatcher {

    private SettingsRepository settings;
    private EncryptedSettingsRepository encSettings;

    private CheckBox checkBoxDeviceWipe;
    private Button buttonSelectRingtone;
    private Button buttonDeletePassword;
    private EditText editTextLockScreenMessage;
    private EditText editTextFmdCommand;

    int colorEnabled;
    int colorDisabled;
    int textColorEnabled;
    int textColorDisabled;

    private static final int REQUEST_CODE_RINGTONE = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_f_m_d_config);

        setupEdgeToEdgeAppBar(findViewById(R.id.appBar));
        setupEdgeToEdgeScrollView(findViewById(R.id.scrollView));

        settings = SettingsRepository.Companion.getInstance(this);
        encSettings = EncryptedSettingsRepository.Companion.getInstance(this);

        checkBoxDeviceWipe = findViewById(R.id.checkBoxWipeData);
        checkBoxDeviceWipe.setChecked((Boolean) settings.get(Settings.SET_WIPE_ENABLED));
        checkBoxDeviceWipe.setOnCheckedChangeListener(this);

        editTextLockScreenMessage = findViewById(R.id.editTextTextLockScreenMessage);
        editTextLockScreenMessage.setText((String) settings.get(Settings.SET_LOCKSCREEN_MESSAGE));
        editTextLockScreenMessage.addTextChangedListener(this);

        colorEnabled = getColor(R.color.md_theme_primary);
        colorDisabled = getColor(R.color.md_theme_error);
        textColorEnabled = getColor(R.color.md_theme_onPrimary);
        textColorDisabled = getColor(R.color.md_theme_onError);

        buttonSelectRingtone = findViewById(R.id.buttonSelectRingTone);
        buttonSelectRingtone.setOnClickListener(this::onSelectRingtoneClicked);

        editTextFmdCommand = findViewById(R.id.editTextFmdCommand);
        editTextFmdCommand.setText((String) settings.get(Settings.SET_FMD_COMMAND));
        editTextFmdCommand.addTextChangedListener(this);

        buttonDeletePassword = findViewById(R.id.buttonDeletePassword);
        buttonDeletePassword.setOnClickListener(this::onEnterDeletePasswordClicked);
        updateDeletePasswordButton();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == checkBoxDeviceWipe) {
            settings.set(Settings.SET_WIPE_ENABLED, isChecked);
            updateDeletePasswordButton();
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
        if (edited == editTextLockScreenMessage.getText()) {
            settings.set(Settings.SET_LOCKSCREEN_MESSAGE, edited.toString());
        } else if (edited == editTextFmdCommand.getText()) {
            if (edited.toString().isEmpty()) {
                Toast.makeText(this, getString(R.string.Toast_Empty_FMDCommand), Toast.LENGTH_LONG).show();
                settings.set(Settings.SET_FMD_COMMAND, "fmd");
            } else {
                settings.set(Settings.SET_FMD_COMMAND, edited.toString().toLowerCase());
            }
        }
    }

    private void onEnterDeletePasswordClicked(View v) {
        new PasswordSetDialog(v.getContext(), (newPassword) -> {
            encSettings.setDeletePassword(newPassword);
            updateDeletePasswordButton();
            return Unit.INSTANCE;
        }, R.string.password_enter, null, true, true).show();
    }

    private void onSelectRingtoneClicked(View v) {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.Settings_Select_Ringtone));
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse((String) settings.get(Settings.SET_RINGER_TONE)));
        try {
            this.startActivityForResult(intent, REQUEST_CODE_RINGTONE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.Settings_no_ringtone_picker), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_RINGTONE) {
            Uri uri = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            settings.set(Settings.SET_RINGER_TONE, uri.toString());
        }
    }

    private void updateDeletePasswordButton() {
        boolean enabled = (boolean) settings.get(Settings.SET_WIPE_ENABLED);
        String password = encSettings.getDeletePassword();
        boolean isPasswordEmpty = password == null || password.isBlank();

        TextView textViewDeletePasswordWarning = findViewById(R.id.textViewDeletePasswordWarning);

        if (isPasswordEmpty) {
            buttonDeletePassword.setBackgroundColor(colorDisabled);
            buttonDeletePassword.setTextColor(textColorDisabled);
            buttonDeletePassword.setText(R.string.password_set);
        } else {
            buttonDeletePassword.setBackgroundColor(colorEnabled);
            buttonDeletePassword.setTextColor(textColorEnabled);
            buttonDeletePassword.setText(R.string.password_change);
        }

        if (enabled && isPasswordEmpty) {
            textViewDeletePasswordWarning.setVisibility(View.VISIBLE);
        } else {
            textViewDeletePasswordWarning.setVisibility(View.GONE);
        }
    }

}