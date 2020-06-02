package com.welie.blessed;




import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * <p>
 * This class acts as a wrapper around logging and allows registering of new logging receivers.
 * </p>
 *
 * <p>
 * In order to turn on LogCat output you can use the {LogCatLogger} class,
 * by putting the following line into your application: (e.g. in onCreate)
 * </p>
 *
 * <code>
 *     HBLogger.registerLogger(new LogCatLogger());
 * </code>
 */
public class HBLogger {

    private static final DelegatingLogger ROOT_LOGGER = new DelegatingLogger();

    public interface LoggerImplementation {
        void logLine(int priority, String tag, String msg, Throwable tr);
    }

    /**
     * Allows registering a LoggerImplementation instance.
     *
     * @param logger The logger instance that should receive log calls
     */
    public static void registerLogger(LoggerImplementation logger) {
        synchronized (ROOT_LOGGER.loggers) {
            ROOT_LOGGER.loggers.add(logger);
        }
    }

    /**
     * Allows unregistering a LoggerImplementation instance.
     *
     * @param logger The logger instance that should no longer receive log calls
     */
    public static void unregisterLogger(LoggerImplementation logger) {
        synchronized (ROOT_LOGGER.loggers) {
            ROOT_LOGGER.loggers.remove(logger);
        }
    }

    /* ---------------------------------------------------------------------------- */
    /* ----------------------- Logging calls -------------------------------------- */
    /* ---------------------------------------------------------------------------- */

    /**
     * Send a verbose log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     *            the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static void v(String tag, String msg) {
        triggerLoggers(Level.INFO, tag, msg);
    }

    /**
     * Send a debug log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     *            the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static void d(String tag, String msg) {
        triggerLoggers(Level.FINE, tag, msg);
    }

     /**
     * Send an info log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     *            the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static void i(String tag, String msg) {
        triggerLoggers(Level.INFO, tag, msg);
    }


    /**
     * Send a warn log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     *            the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static void w(String tag, String msg) {
        triggerLoggers(Level.WARNING, tag, msg);
    }

    /**
     * Send an error log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     *            the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    public static void e(String tag, String msg) {
        triggerLoggers(Level.SEVERE, tag, msg);
    }

    public static void e(String tag, Exception e) { triggerLoggers(Level.SEVERE, tag, e.getMessage(), e);}
     /**
     * What a Terrible Failure: Report a condition that should never happen.
     * The error will always be logged at level severe with the call stack.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public static void wtf(String tag, String msg) {
        triggerLoggers(Level.SEVERE, tag, msg);
    }

    final private static Logger LOGGER = Logger.getLogger("HBLogger");

    private static FileHandler fh;

    public static void activateFileLogging(String logFileName) {
        try {
            fh = new FileHandler(logFileName, 10*1024*2024, 30, true);
            LOGGER.addHandler(fh);
            LOGGER.setUseParentHandlers(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void triggerLoggers(Level priority, String tag, String msg) {
        if(priority == Level.INFO) {
            LOGGER.info(msg);
        } else if(priority == Level.WARNING) {
            LOGGER.warning(msg);
        } else if(priority == Level.SEVERE) {
            LOGGER.severe(msg);
        } else if(priority == Level.FINE) {
            LOGGER.fine(msg);
        }
    }

    private static void triggerLoggers(Level priority, String tag, String msg, Throwable tr) {
        LOGGER.severe(tr.getMessage());
        LOGGER.log(priority, msg, tr);
    }

    /* ---------------------------------------------------------------------------- */
    /* ----------------------- Logger implementations ----------------------------- */
    /* ---------------------------------------------------------------------------- */

    private static class DelegatingLogger implements LoggerImplementation {

        private final List<LoggerImplementation> loggers = new ArrayList<>();

        @Override
        public void logLine(final int priority, final String tag, final String msg, final Throwable tr) {
            for (final LoggerImplementation logger : loggers) {
                logger.logLine(priority, tag, msg, tr);
            }
        }
    }

//    public static class LogCatLogger implements LoggerImplementation {
//
//        @Override
//        public void logLine(int priority, String tag, String msg, Throwable tr) {
//            if (priority == Level.SEVERE.intValue()) {
//                LOGGER.severe(tag + msg);
//            }
//            else {
//                LOGGER.info(tag + " " + msg + '\n');
//            }
//        }
//    }
}