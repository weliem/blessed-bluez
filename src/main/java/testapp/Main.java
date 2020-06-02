package testapp;

import com.welie.blessed.HBLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;

public class Main {

    private static final String TAG = "APP";

    static {
        InputStream stream = Main.class.getClassLoader().
                getResourceAsStream("logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(stream);
        } catch (IOException e) {
            HBLogger.e(TAG, "Could not load logging.properties");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Hello World");
        BluetoothHandler bluetoothHandler = new BluetoothHandler();
    }
}
