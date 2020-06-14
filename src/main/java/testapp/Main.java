package testapp;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {

    private static final String TAG = "APP";
    private static final Logger logger = Logger.getLogger(TAG);

    static {
        InputStream stream = Main.class.getClassLoader().
                getResourceAsStream("logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(stream);
        } catch (IOException e) {
            logger.severe("Could not load logging.properties");
        }
    }

    public static void main(String[] args) throws Exception {
        BluetoothHandler bluetoothHandler = new BluetoothHandler();
    }
}
