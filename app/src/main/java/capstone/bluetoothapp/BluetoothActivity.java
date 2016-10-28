package capstone.bluetoothapp;

import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class BluetoothActivity extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth);

        Button connectButton = (Button) findViewById(R.id.connectButton);
        assert connectButton != null;
        connectButton.setOnClickListener(this);

        Button blinkButton = (Button) findViewById(R.id.blinkButton);
        assert blinkButton != null;
        blinkButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(100);

        switch(v.getId()) {
            case R.id.connectButton:
                bluetoothConnect();
                break;
            case R.id.blinkButton:
                blink();
                break;
            default:
                break;
        }

    }

    private void bluetoothConnect() {
        // TODO STUB
    }

    private void blink() {
        // TODO STUB
    }
}
